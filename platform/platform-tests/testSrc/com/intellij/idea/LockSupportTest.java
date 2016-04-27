/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.intellij.idea;

import com.intellij.openapi.util.io.FileUtil;
import junit.framework.TestCase;

import java.io.File;

/**
 * @author mike
 */
public class LockSupportTest extends TestCase {
  private String tempDir;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    tempDir = FileUtil.createTempDirectory("idea-lock-support-test.", ".dir").getPath();
  }

  @Override
  protected void tearDown() throws Exception {
    FileUtil.delete(new File(tempDir));
    super.tearDown();
  }

  public void testLock() throws Exception {
    final SocketLock lock = new SocketLock();
    try {
      assertEquals(SocketLock.ActivateStatus.NO_INSTANCE, lock.lock(tempDir + "/abc", false));
    }
    finally {
      lock.dispose();
    }
  }

  public void testTwoLocks() throws Exception {
    final SocketLock lock1 = new SocketLock();
    final SocketLock lock2 = new SocketLock();

    try {
      assertEquals(SocketLock.ActivateStatus.NO_INSTANCE, lock1.lock(tempDir + "/1", false));
      assertEquals(SocketLock.ActivateStatus.NO_INSTANCE, lock1.lock(tempDir + "/1.1", false));
      assertEquals(SocketLock.ActivateStatus.NO_INSTANCE, lock2.lock(tempDir + "/2", false));
      assertEquals(SocketLock.ActivateStatus.ACTIVATED, lock1.lock(tempDir + "/2", false));
      assertEquals(SocketLock.ActivateStatus.ACTIVATED, lock2.lock(tempDir + "/1", false));
      assertEquals(SocketLock.ActivateStatus.ACTIVATED, lock2.lock(tempDir + "/1.1", false));
    }
    finally {
      lock1.dispose();
      lock2.dispose();
    }
  }

  public void testDispose() throws Exception {
    final SocketLock lock1 = new SocketLock();
    final SocketLock lock2 = new SocketLock();

    assertEquals(SocketLock.ActivateStatus.NO_INSTANCE, lock1.lock(tempDir + "/1", false));
    assertEquals(SocketLock.ActivateStatus.ACTIVATED, lock2.lock(tempDir + "/1", false));

    lock1.dispose();
    assertEquals(SocketLock.ActivateStatus.NO_INSTANCE, lock2.lock(tempDir + "/1", false));
    lock2.dispose();
  }
}