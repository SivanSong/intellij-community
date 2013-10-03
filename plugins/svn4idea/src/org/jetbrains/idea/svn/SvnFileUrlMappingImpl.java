/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn;

import com.intellij.lifecycle.PeriodicalTasksCloser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbAwareRunnable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ObjectsConvertor;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.ThreadLocalDefendedInvoker;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.InvokeAfterUpdateMode;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vcs.impl.VcsInitObject;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Convertor;
import com.intellij.util.messages.MessageBus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.util.SVNURLUtil;
import org.tmatesoft.svn.core.wc.SVNInfo;
import org.tmatesoft.svn.core.wc.SVNStatus;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@State(
  name = "SvnFileUrlMappingImpl",
  storages = {
    @Storage(
      file = StoragePathMacros.WORKSPACE_FILE
    )}
)
public class SvnFileUrlMappingImpl implements SvnFileUrlMapping, PersistentStateComponent<SvnMappingSavedPart>, ProjectComponent {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.idea.svn.SvnFileUrlMappingImpl");

  private final SvnCompatibilityChecker myChecker;
  private final Object myMonitor = new Object();
  // strictly: what real roots are under what vcs mappings
  private final SvnMapping myMapping;
  // grouped; if there are several mappings one under another, will return the upmost
  private final SvnMapping myMoreRealMapping;
  private final MyRootsHelper myHelper;
  private final Project myProject;
  private final NestedCopiesHolder myNestedCopiesHolder;
  private boolean myInitialized;
  private boolean myInitedReloaded;

  private static class MyRootsHelper extends ThreadLocalDefendedInvoker<VirtualFile[]> {
    private final ProjectLevelVcsManager myPlVcsManager;

    private MyRootsHelper(final ProjectLevelVcsManager vcsManager) {
      myPlVcsManager = vcsManager;
    }

    protected VirtualFile[] execute(Project project) {
      return myPlVcsManager.getRootsUnderVcs(SvnVcs.getInstance(project));
    }
  }

  public static SvnFileUrlMappingImpl getInstance(final Project project) {
    return PeriodicalTasksCloser.getInstance().safeGetComponent(project, SvnFileUrlMappingImpl.class);
  }

  @SuppressWarnings("UnusedDeclaration")
  private SvnFileUrlMappingImpl(final Project project, final ProjectLevelVcsManager vcsManager) {
    myProject = project;
    myMapping = new SvnMapping();
    myMoreRealMapping = new SvnMapping();
    myHelper = new MyRootsHelper(vcsManager);
    myChecker = new SvnCompatibilityChecker(project);
    myNestedCopiesHolder = new NestedCopiesHolder();
  }

  @Nullable
  public SVNURL getUrlForFile(final File file) {
    final RootUrlInfo rootUrlInfo = getWcRootForFilePath(file);
    if (rootUrlInfo == null) {
      return null;
    }

    final String absolutePath = file.getAbsolutePath();
    final String rootAbsPath = rootUrlInfo.getIoFile().getAbsolutePath();
    if (absolutePath.length() < rootAbsPath.length()) {
      // remove last separator from etalon name
      if (absolutePath.equals(rootAbsPath.substring(0, rootAbsPath.length() - 1))) {
        return rootUrlInfo.getAbsoluteUrlAsUrl();
      }
      return null;
    }
    final String relativePath = absolutePath.substring(rootAbsPath.length());
    try {
      return rootUrlInfo.getAbsoluteUrlAsUrl().appendPath(FileUtil.toSystemIndependentName(relativePath), true);
    }
    catch (SVNException e) {
      LOG.info(e);
      return null;
    }
  }

  @Nullable
  public String getLocalPath(final String url) {
    synchronized (myMonitor) {
      final String rootUrl = getUrlRootForUrl(url);
      if (rootUrl == null) {
        return null;
      }
      final RootUrlInfo parentInfo = myMoreRealMapping.byUrl(rootUrl);
      if (parentInfo == null) {
        return null;
      }

      return fileByUrl(parentInfo.getIoFile().getAbsolutePath(), rootUrl, url).getAbsolutePath();
    }
  }

  public static File fileByUrl(final String parentPath, final String parentUrl, final String childUrl) {
    return new File(parentPath, childUrl.substring(parentUrl.length()));
  }

  @Nullable
  public RootUrlInfo getWcRootForFilePath(final File file) {
    synchronized (myMonitor) {
      final String root = getRootForPath(file);
      if (root == null) {
        return null;
      }

      return myMoreRealMapping.byFile(root);
    }
  }

  public boolean rootsDiffer() {
    synchronized (myMonitor) {
      return myMapping.isRootsDifferFromSettings();
    }
  }

  @Nullable
  public RootUrlInfo getWcRootForUrl(final String url) {
    synchronized (myMonitor) {
      final String rootUrl = getUrlRootForUrl(url);
      if (rootUrl == null) {
        return null;
      }

      final RootUrlInfo result = myMoreRealMapping.byUrl(rootUrl);
      if (result == null) {
        LOG.info("Inconsistent maps for url:" + url + " found root url: " + rootUrl);
        return null;
      }
      return result;
    }
  }

  /**
   * Returns real working copies roots - if there is <Project Root> -> Subversion setting,
   * and there is one working copy, will return one root
   */
  public List<RootUrlInfo> getAllWcInfos() {
    synchronized (myMonitor) {
      // a copy is created inside
      return myMoreRealMapping.getAllCopies();
    }
  }

  public List<VirtualFile> convertRoots(final List<VirtualFile> result) {
    if (ThreadLocalDefendedInvoker.isInside()) return result;

    synchronized (myMonitor) {
      final List<VirtualFile> cachedRoots = myMoreRealMapping.getUnderVcsRoots();
      final List<VirtualFile> lonelyRoots = myMoreRealMapping.getLonelyRoots();
      if (! lonelyRoots.isEmpty()) {
        myChecker.reportNoRoots(lonelyRoots);
      }
      if (cachedRoots.isEmpty()) {
        // todo +-
        return result;
      }
      return cachedRoots;
    }
  }

  public void acceptNestedData(final Set<NestedCopyInfo> set) {
    myNestedCopiesHolder.add(set);
  }

  private boolean init() {
    synchronized (myMonitor) {
      final boolean result = myInitialized;
      myInitialized = true;
      return result;
    }
  }

  public void realRefresh(final Runnable afterRefreshCallback) {
    final SvnVcs vcs = SvnVcs.getInstance(myProject);
    final VirtualFile[] roots = myHelper.executeDefended(myProject);

    final CopiesApplier copiesApplier = new CopiesApplier();
    final CopiesDetector copiesDetector = new CopiesDetector(vcs, copiesApplier, myNestedCopiesHolder);
    // do not send additional request for nested copies when in init state
    copiesDetector.detectCopyRoots(roots, init(), afterRefreshCallback);
  }

  private class CopiesApplier {

    public void apply(@NotNull final List<RootUrlInfo> roots, @NotNull final List<VirtualFile> lonelyRoots) {
      final SvnMapping mapping = new SvnMapping();
      mapping.addAll(roots);
      mapping.reportLonelyRoots(lonelyRoots);

      final SvnMapping filteredMapping = new SvnMapping();
      filteredMapping.addAll(new UniqueRootsFilter().filter(roots));

      runUpdateMappings(mapping, filteredMapping);
    }

    private void runUpdateMappings(@NotNull final SvnMapping mapping, @NotNull final SvnMapping filteredMapping) {
      // TODO: Not clear so far why read action is used here - may be because of ROOTS_RELOADED message sent?
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          if (myProject.isDisposed()) return;

          boolean mappingsChanged = updateMappings(mapping, filteredMapping);

          notifyRootsReloaded(mappingsChanged);
        }
      });
    }

    private boolean updateMappings(@NotNull SvnMapping mapping, @NotNull SvnMapping filteredMapping) {
      boolean mappingsChanged;
      synchronized (myMonitor) {
        mappingsChanged = ! myMapping.equals(mapping);
        if (mappingsChanged) {
          mappingsChanged = ! myMoreRealMapping.equals(filteredMapping);
        }
        myMapping.copyFrom(mapping);
        myMoreRealMapping.copyFrom(filteredMapping);
      }
      return mappingsChanged;
    }

    private void notifyRootsReloaded(boolean mappingsChanged) {
      final MessageBus bus = myProject.getMessageBus();
      if (mappingsChanged || ! myInitedReloaded) {
        myInitedReloaded = true;
        // all listeners are asynchronous
        bus.syncPublisher(SvnVcs.ROOTS_RELOADED).consume(true);
        bus.syncPublisher(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED_IN_PLUGIN).directoryMappingChanged();
      } else {
        bus.syncPublisher(SvnVcs.ROOTS_RELOADED).consume(false);
      }
    }
  }

  private static class CopiesDetector {
    private final SvnVcs myVcs;
    private final CopiesApplier myApplier;
    private final List<VirtualFile> myLonelyRoots;
    private final List<RootUrlInfo> myTopRoots;
    private final RepositoryRoots myRepositoryRoots;
    private final NestedCopiesHolder myNestedCopiesHolder;

    private CopiesDetector(final SvnVcs vcs, final CopiesApplier applier, @NotNull final NestedCopiesHolder holder) {
      myVcs = vcs;
      myApplier = applier;
      myNestedCopiesHolder = holder;
      myTopRoots = new ArrayList<RootUrlInfo>();
      myLonelyRoots = new ArrayList<VirtualFile>();
      myRepositoryRoots = new RepositoryRoots(myVcs);
    }

    public void detectCopyRoots(final VirtualFile[] roots, final boolean clearState, Runnable callback) {
      for (final VirtualFile vcsRoot : roots) {
        // go into nested = false => only find a working copys below passed roots, but not nested
        final List<Node> foundRoots = new ForNestedRootChecker(myVcs).getAllNestedWorkingCopies(vcsRoot, false);
        registerLonelyRoots(vcsRoot, foundRoots);
        registerTopRoots(vcsRoot, foundRoots);
      }

      addNestedRoots(clearState, callback);
    }

    private void registerLonelyRoots(VirtualFile vcsRoot, List<Node> foundRoots) {
      if (foundRoots.isEmpty()) {
        myLonelyRoots.add(vcsRoot);
      }
    }

    private void registerTopRoots(@NotNull VirtualFile vcsRoot, @NotNull List<Node> foundRoots) {
      // filter out bad(?) items
      for (Node foundRoot : foundRoots) {
        final SVNURL repoRoot = foundRoot.getRepositoryRootUrl();
        if (repoRoot == null) {
          LOG.info("Error: cannot find repository URL for versioned folder: " + foundRoot.getFile().getPath());
        } else {
          myRepositoryRoots.register(repoRoot);
          myTopRoots.add(new RootUrlInfo(repoRoot, foundRoot.getUrl(), SvnFormatSelector.findRootAndGetFormat(
            new File(foundRoot.getFile().getPath())), foundRoot.getFile(), vcsRoot));
        }
      }
    }

    private void addNestedRoots(final boolean clearState, final Runnable callback) {
      final List<VirtualFile> basicVfRoots = ObjectsConvertor.convert(myTopRoots, new Convertor<RootUrlInfo, VirtualFile>() {
        public VirtualFile convert(final RootUrlInfo real) {
          return real.getVirtualFile();
        }
      });

      final ChangeListManager clManager = ChangeListManager.getInstance(myVcs.getProject());

      if (clearState) {
        // clear what was reported before (could be for currently-not-existing roots)
        myNestedCopiesHolder.getAndClear();
      }
      clManager.invokeAfterUpdate(new Runnable() {
        public void run() {
          final List<RootUrlInfo> nestedRoots = new ArrayList<RootUrlInfo>();

          for (NestedCopyInfo info : myNestedCopiesHolder.getAndClear()) {
            if (NestedCopyType.external.equals(info.getType()) || NestedCopyType.switched.equals(info.getType())) {
              RootUrlInfo topRoot = findTopRoot(VfsUtilCore.virtualToIoFile(info.getFile()));

              if (topRoot != null) {
                topRoot.setType(info.getType());
                continue;
              }
              // TODO: Not clear so far why we need to "refresh" urls and format here
              if (!refreshPointInfo(info)) {
                continue;
              }
            }
            registerRootUrlFromNestedPoint(info, nestedRoots);
          }
          // check those top roots which ARE externals, but that was not detected due to they itself were the status request target
          //new SvnNestedTypeRechecker(myVcs.getProject(), myTopRoots).run();

          myTopRoots.addAll(nestedRoots);
          myApplier.apply(myTopRoots, myLonelyRoots);

          callback.run();
        }
      }, InvokeAfterUpdateMode.SILENT_CALLBACK_POOLED, null, new Consumer<VcsDirtyScopeManager>() {
        public void consume(VcsDirtyScopeManager vcsDirtyScopeManager) {
          if (clearState) {
            vcsDirtyScopeManager.filesDirty(null, basicVfRoots);
          }
        }
      }, null);
    }

    private void registerRootUrlFromNestedPoint(@NotNull NestedCopyInfo info, @NotNull List<RootUrlInfo> nestedRoots) {
      // TODO: Seems there could be issues if myTopRoots contains nested roots => RootUrlInfo.myRoot could be incorrect
      // TODO: (not nearest ancestor) for new RootUrlInfo
      RootUrlInfo topRoot = findAncestorTopRoot(info.getFile());

      if (topRoot != null) {
        SVNURL repoRoot = info.getRootURL();
        repoRoot = repoRoot == null ? myRepositoryRoots.ask(info.getUrl(), info.getFile()) : repoRoot;
        if (repoRoot != null) {
          final RootUrlInfo rootInfo = new RootUrlInfo(repoRoot, info.getUrl(), info.getFormat(), info.getFile(), topRoot.getRoot());
          rootInfo.setType(info.getType());
          nestedRoots.add(rootInfo);
        }
      }
    }

    private boolean refreshPointInfo(@NotNull NestedCopyInfo info) {
      boolean refreshed = false;

      // TODO: No checked exceptions are thrown - remove catch/LOG.error/rethrow to fix real cause if any
      try {
        final File infoFile = VfsUtilCore.virtualToIoFile(info.getFile());
        final SVNStatus svnStatus = SvnUtil.getStatus(myVcs, infoFile);

        if (svnStatus != null && svnStatus.getURL() != null) {
          info.setUrl(svnStatus.getURL());
          info.setFormat(myVcs.getWorkingCopyFormat(infoFile));
          if (svnStatus.getRepositoryRootURL() != null) {
            info.setRootURL(svnStatus.getRepositoryRootURL());
          }
          refreshed = true;
        }
      }
      catch (Exception e) {
        LOG.info(e);
      }

      return refreshed;
    }

    @Nullable
    private RootUrlInfo findTopRoot(@NotNull final File file) {
      return ContainerUtil.find(myTopRoots, new Condition<RootUrlInfo>() {
        @Override
        public boolean value(RootUrlInfo topRoot) {
          return FileUtil.filesEqual(topRoot.getIoFile(), file);
        }
      });
    }

    @Nullable
    private RootUrlInfo findAncestorTopRoot(@NotNull final VirtualFile file) {
      return ContainerUtil.find(myTopRoots, new Condition<RootUrlInfo>() {
        @Override
        public boolean value(RootUrlInfo topRoot) {
          return VfsUtilCore.isAncestor(topRoot.getVirtualFile(), file, true);
        }
      });
    }
  }

  private static class RepositoryRoots {
    private final SvnVcs myVcs;
    private final Set<SVNURL> myRoots;

    private RepositoryRoots(final SvnVcs vcs) {
      myVcs = vcs;
      myRoots = new HashSet<SVNURL>();
    }

    public void register(final SVNURL url) {
      myRoots.add(url);
    }

    public SVNURL ask(final SVNURL url, VirtualFile file) {
      for (SVNURL root : myRoots) {
        if (root.equals(SVNURLUtil.getCommonURLAncestor(root, url))) {
          return root;
        }
      }
      final SVNURL newUrl = SvnUtil.getRepositoryRoot(myVcs, new File(file.getPath()));
      if (newUrl != null) {
        myRoots.add(newUrl);
        return newUrl;
      }
      return null;
    }
  }

  @Nullable
  public String getUrlRootForUrl(final String currentUrl) {
    for (String url : myMoreRealMapping.getUrls()) {
      if (SVNPathUtil.isAncestor(url, currentUrl)) {
        return url;
      }
    }
    return null;
  }

  @Nullable
  public String getRootForPath(final File currentPath) {
    String convertedPath = currentPath.getAbsolutePath();
    convertedPath = (currentPath.isDirectory() && (! convertedPath.endsWith(File.separator))) ? convertedPath + File.separator :
        convertedPath;
    synchronized (myMonitor) {
      return myMoreRealMapping.getRootForPath(convertedPath);
    }
  }

  public VirtualFile[] getNotFilteredRoots() {
    return myHelper.executeDefended(myProject);
  }

  public boolean isEmpty() {
    synchronized (myMonitor) {
      return myMapping.isEmpty();
    }
  }

  public SvnMappingSavedPart getState() {
    final SvnMappingSavedPart result = new SvnMappingSavedPart();

    final SvnMapping mapping = new SvnMapping();
    final SvnMapping realMapping = new SvnMapping();
    synchronized (myMonitor) {
      mapping.copyFrom(myMapping);
      realMapping.copyFrom(myMoreRealMapping);
    }

    for (RootUrlInfo info : mapping.getAllCopies()) {
      result.add(convert(info));
    }
    for (RootUrlInfo info : realMapping.getAllCopies()) {
      result.addReal(convert(info));
    }
    return result;
  }

  private SvnCopyRootSimple convert(final RootUrlInfo info) {
    final SvnCopyRootSimple copy = new SvnCopyRootSimple();
    copy.myVcsRoot = FileUtil.toSystemDependentName(info.getRoot().getPath());
    copy.myCopyRoot = info.getIoFile().getAbsolutePath();
    return copy;
  }

  public void loadState(final SvnMappingSavedPart state) {
    ((ProjectLevelVcsManagerImpl) ProjectLevelVcsManager.getInstance(myProject)).addInitializationRequest(
      VcsInitObject.AFTER_COMMON, new DumbAwareRunnable() {
        public void run() {
          ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
              final SvnMapping mapping = new SvnMapping();
              final SvnMapping realMapping = new SvnMapping();
              try {
                fillMapping(mapping, state.getMappingRoots());
                fillMapping(realMapping, state.getMoreRealMappingRoots());
              } catch (ProcessCanceledException e) {
                throw e;
              } catch (Throwable t) {
                LOG.info(t);
                return;
              }

              synchronized (myMonitor) {
                myMapping.copyFrom(mapping);
                myMoreRealMapping.copyFrom(realMapping);
              }
            }
          });
        }
    });
  }

  private void fillMapping(final SvnMapping mapping, final List<SvnCopyRootSimple> list) {
    final LocalFileSystem lfs = LocalFileSystem.getInstance();

    for (SvnCopyRootSimple simple : list) {
      final VirtualFile copyRoot = lfs.findFileByIoFile(new File(simple.myCopyRoot));
      final VirtualFile vcsRoot = lfs.findFileByIoFile(new File(simple.myVcsRoot));

      if (copyRoot == null || vcsRoot == null) continue;

      final SvnVcs vcs = SvnVcs.getInstance(myProject);
      final SVNInfo svnInfo = vcs.getInfo(copyRoot);
      if ((svnInfo == null) || (svnInfo.getRepositoryRootURL() == null)) continue;

      final RootUrlInfo info =
        new RootUrlInfo(svnInfo.getRepositoryRootURL(), svnInfo.getURL(), SvnFormatSelector.findRootAndGetFormat(svnInfo.getFile()),
                        copyRoot, vcsRoot);
      mapping.add(info);
    }
  }

  public void projectOpened() {
  }

  public void projectClosed() {
  }

  @NotNull
  public String getComponentName() {
    return "SvnFileUrlMappingImpl";
  }

  public void initComponent() {
  }

  public void disposeComponent() {
  }
}
