package com.intellij.lang.ant.psi.impl.reference;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.ant.AntBundle;
import com.intellij.lang.ant.AntSupport;
import com.intellij.lang.ant.psi.*;
import com.intellij.lang.ant.psi.impl.AntAntImpl;
import com.intellij.lang.ant.quickfix.AntCreateTargetFix;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLock;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.StringBuilderSpinAllocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AntTargetReference extends AntGenericReference {
  private boolean myShouldBeSkippedByAnnotator;

  public AntTargetReference(final AntElement antElement, final String str, final TextRange textRange, final XmlAttribute attribute) {
    super(antElement, str, textRange, attribute);
    setShouldBeSkippedByAnnotator(false);
  }

  public PsiElement handleElementRename(String newElementName) throws IncorrectOperationException {
    final AntElement element = getElement();
    if (element instanceof AntProject || element instanceof AntCall || element instanceof AntAnt) {
      getAttribute().setValue(newElementName);
    }
    else if (element instanceof AntTarget) {
      int start = getElementStartOffset() + getReferenceStartOffset() - getAttributeValueStartOffset();
      final String value = getAttribute().getValue();
      final StringBuilder builder = StringBuilderSpinAllocator.alloc();
      try {
        if (start > 0) {
          builder.append(value.substring(0, start));
        }
        builder.append(newElementName);
        if (value.length() > start + getRangeInElement().getLength()) {
          builder.append(value.substring(start + getRangeInElement().getLength()));
        }
        getAttribute().setValue(builder.toString());
      }
      finally {
        StringBuilderSpinAllocator.dispose(builder);
      }
    }
    return element;
  }

  @Nullable
  public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {
    if (element instanceof AntTarget) {
      final PsiNamedElement psiNamedElement = (PsiNamedElement)element;
      return handleElementRename(psiNamedElement.getName());
    }
    throw new IncorrectOperationException("Can bind only to ant targets.");
  }


  public PsiElement resolve() {
    final String name = getCanonicalRepresentationText();
    if (name == null) return null;

    final AntElement element = getElement();
    AntTarget result = null;

    if (element instanceof AntAntImpl) {
      final PsiFile psiFile = ((AntAntImpl)element).getCalledAntFile();
      if (psiFile != null) {
        AntFile antFile = AntSupport.getAntFile(psiFile);
        if (antFile != null) {
          final AntProject antProject = antFile.getAntProject();
          if (antProject != null) {
            result = antProject.getTarget(name);
          }
        }
      }
    }
    if (result == null) {
      final AntProject project = element.getAntProject();
      result = project.getTarget(name);
      if (result == null) {
        for (final AntTarget target : project.getImportedTargets()) {
          if (name.equals(target.getName())) {
            result = target;
            break;
          }
        }
        if (result == null) {
          for (final AntTarget target : project.getImportedTargets()) {
            if (name.equals(target.getQualifiedName())) {
              result = target;
              break;
            }
          }
        }
      }
    }

    return result;
  }

  public String getUnresolvedMessagePattern() {
    return AntBundle.message("cannot.resolve.target", getCanonicalRepresentationText());
  }

  public boolean shouldBeSkippedByAnnotator() {
    synchronized (PsiLock.LOCK) {
      return myShouldBeSkippedByAnnotator;
    }
  }

  public void setShouldBeSkippedByAnnotator(boolean value) {
    synchronized (PsiLock.LOCK) {
      myShouldBeSkippedByAnnotator = value;
    }
  }

  public Object[] getVariants() {
    final AntElement element = getElement();
    if (element instanceof AntAntImpl) {
      final PsiFile psiFile = ((AntAntImpl)element).getCalledAntFile();
      if (psiFile != null) {
        AntFile antFile;
        if (psiFile instanceof AntFile) {
          antFile = (AntFile)psiFile;
        }
        else {
          antFile = AntSupport.getAntFile(psiFile);
        }
        final AntProject project = (antFile == null) ? null : antFile.getAntProject();
        if (project != null) {
          return project.getTargets();
        }
      }
    }

    List<AntTarget> result = new ArrayList<AntTarget>();

    final AntProject project = element.getAntProject();
    final AntTarget[] targets = project.getTargets();
    for (final AntTarget target : targets) {
      if (target != element) {
        result.add(target);
      }
    }

    result.addAll(Arrays.asList(project.getImportedTargets()));
    
    return result.toArray();
  }

  @NotNull
  public IntentionAction[] getFixes() {
    final String name = getCanonicalRepresentationText();
    if (name == null || name.length() == 0) return EMPTY_INTENTIONS;

    final AntProject project = getElement().getAntProject();
    final AntFile[] importedFiles = project.getImportedFiles();
    final List<IntentionAction> result = new ArrayList<IntentionAction>(importedFiles.length + 1);
    result.add(new AntCreateTargetFix(this));
    for (final AntFile file : importedFiles) {
      if (file.isPhysical()) {
        result.add(new AntCreateTargetFix(this, file));
      }
    }
    return result.toArray(new IntentionAction[result.size()]);
  }

  private int getElementStartOffset() {
    return getElement().getTextRange().getStartOffset();
  }

  private int getReferenceStartOffset() {
    return getRangeInElement().getStartOffset();
  }

  private int getAttributeValueStartOffset() {
    final XmlAttribute attr = getAttribute();
    final XmlAttributeValue valueElement = attr.getValueElement();
    return (valueElement == null) ? attr.getTextRange().getEndOffset() + 1 : valueElement.getTextRange().getStartOffset() + 1;
  }
}