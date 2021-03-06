// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.acceptance.edit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.AcceptanceTestRequestScope;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestSession;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.FileContentUtil;
import com.google.gerrit.server.edit.ChangeEdit;
import com.google.gerrit.server.edit.ChangeEditModifier;
import com.google.gerrit.server.edit.ChangeEditUtil;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.util.Providers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

@NoHttpd
public class ChangeEditIT extends AbstractDaemonTest {

  private final static String FILE_NAME = "foo";
  private final static String FILE_NAME2 = "foo2";
  private final static byte[] CONTENT_OLD = "bar".getBytes(UTF_8);
  private final static byte[] CONTENT_NEW = "baz".getBytes(UTF_8);
  private final static byte[] CONTENT_NEW2 = "qux".getBytes(UTF_8);

  @Inject
  private SchemaFactory<ReviewDb> reviewDbProvider;

  @Inject
  private IdentifiedUser.GenericFactory identifiedUserFactory;

  @Inject
  private PushOneCommit.Factory pushFactory;

  @Inject
  ChangeEditUtil editUtil;

  @Inject
  private ChangeEditModifier modifier;

  @Inject
  private FileContentUtil fileUtil;

  @Inject
  private AcceptanceTestRequestScope atrScope;

  private ReviewDb db;
  private Change change;
  private Change change2;
  private PatchSet ps;
  private PatchSet ps2;
  RestSession session;

  @Before
  public void setUp() throws Exception {
    db = reviewDbProvider.open();
    String changeId = newChange(git, admin.getIdent());
    change = getChange(changeId);
    ps = getCurrentPatchSet(changeId);
    assertNotNull(ps);
    changeId = newChange2(git, admin.getIdent());
    change2 = getChange(changeId);
    assertNotNull(change2);
    ps2 = getCurrentPatchSet(changeId);
    assertNotNull(ps2);
    session = new RestSession(server, admin);
    atrScope.set(atrScope.newContext(reviewDbProvider, sshSession,
        identifiedUserFactory.create(Providers.of(db), admin.getId())));
  }

  @After
  public void cleanup() {
    db.close();
  }

  @Test
  public void deleteEdit() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            editUtil.byChange(change).get(),
            FILE_NAME,
            CONTENT_NEW));
    editUtil.delete(editUtil.byChange(change).get());
    assertFalse(editUtil.byChange(change).isPresent());
  }

  @Test
  public void publishEdit() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            editUtil.byChange(change).get(),
            FILE_NAME,
            CONTENT_NEW));
    editUtil.publish(editUtil.byChange(change).get());
    assertFalse(editUtil.byChange(change).isPresent());
  }

  @Test
  public void updateExistingFile() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    Optional<ChangeEdit> edit = editUtil.byChange(change);
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            edit.get(),
            FILE_NAME,
            CONTENT_NEW));
    edit = editUtil.byChange(change);
    assertArrayEquals(CONTENT_NEW,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME)));
    editUtil.delete(edit.get());
    edit = editUtil.byChange(change);
    assertFalse(edit.isPresent());
  }

  @Test
  public void deleteExistingFile() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    Optional<ChangeEdit> edit = editUtil.byChange(change);
    assertEquals(RefUpdate.Result.FORCED,
        modifier.deleteFile(
            edit.get(),
            FILE_NAME));
    edit = editUtil.byChange(change);
    try {
      fileUtil.getContent(edit.get().getChange().getProject(),
          edit.get().getRevision().get(), FILE_NAME);
      fail("ResourceNotFoundException expected");
    } catch (ResourceNotFoundException rnfe) {
    }
  }

  @Test
  public void restoreDeletedFileInEdit() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    Optional<ChangeEdit> edit = editUtil.byChange(change);
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            edit.get(),
            FILE_NAME,
            CONTENT_NEW));
    edit = editUtil.byChange(change);
    assertArrayEquals(CONTENT_NEW,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME)));
    assertEquals(RefUpdate.Result.FORCED,
        modifier.deleteFile(
            edit.get(),
            FILE_NAME));
    edit = editUtil.byChange(change);
    try {
      fileUtil.getContent(edit.get().getChange().getProject(),
          edit.get().getRevision().get(), FILE_NAME);
      fail("ResourceNotFoundException expected");
    } catch (ResourceNotFoundException rnfe) {
    }
    assertEquals(RefUpdate.Result.FORCED,
        modifier.restoreFile(
            edit.get(),
            FILE_NAME));
    edit = editUtil.byChange(change);
    assertArrayEquals(CONTENT_OLD,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME)));
  }

  @Test
  public void restoreDeletedFileInPatchSet() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change2,
            ps2));
    Optional<ChangeEdit> edit = editUtil.byChange(change2);
    assertEquals(RefUpdate.Result.FORCED,
        modifier.restoreFile(
            edit.get(),
            FILE_NAME));
    edit = editUtil.byChange(change2);
    assertArrayEquals(CONTENT_OLD,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME)));
  }

  @Test
  public void amendExistingFile() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    Optional<ChangeEdit> edit = editUtil.byChange(change);
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            edit.get(),
            FILE_NAME,
            CONTENT_NEW));
    edit = editUtil.byChange(change);
    assertArrayEquals(CONTENT_NEW,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME)));
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            edit.get(),
            FILE_NAME,
            CONTENT_NEW2));
    edit = editUtil.byChange(change);
    assertArrayEquals(CONTENT_NEW2,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME)));
  }

  @Test
  public void addNewFile() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    Optional<ChangeEdit> edit = editUtil.byChange(change);
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            edit.get(),
            FILE_NAME2,
            CONTENT_NEW));
    edit = editUtil.byChange(change);
    assertArrayEquals(CONTENT_NEW,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME2)));
  }

  @Test
  public void addNewFileAndAmend() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    Optional<ChangeEdit> edit = editUtil.byChange(change);
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            edit.get(),
            FILE_NAME2,
            CONTENT_NEW));
    edit = editUtil.byChange(change);
    assertArrayEquals(CONTENT_NEW,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME2)));
    assertEquals(RefUpdate.Result.FORCED,
        modifier.modifyFile(
            edit.get(),
            FILE_NAME2,
            CONTENT_NEW2));
    edit = editUtil.byChange(change);
    assertArrayEquals(CONTENT_NEW2,
        toBytes(fileUtil.getContent(edit.get().getChange().getProject(),
            edit.get().getRevision().get(), FILE_NAME2)));
  }

  @Test
  public void writeNoChanges() throws Exception {
    assertEquals(RefUpdate.Result.NEW,
        modifier.createEdit(
            change,
            ps));
    try {
      modifier.modifyFile(
          editUtil.byChange(change).get(),
          FILE_NAME,
          CONTENT_OLD);
      fail();
    } catch (InvalidChangeOperationException e) {
      assertEquals("no changes were made", e.getMessage());
    }
  }

  private String newChange(Git git, PersonIdent ident) throws Exception {
    PushOneCommit push =
        pushFactory.create(db, ident, PushOneCommit.SUBJECT, FILE_NAME,
            new String(CONTENT_OLD));
    return push.to(git, "refs/for/master").getChangeId();
  }

  private String newChange2(Git git, PersonIdent ident) throws Exception {
    PushOneCommit push =
        pushFactory.create(db, ident, PushOneCommit.SUBJECT, FILE_NAME,
            new String(CONTENT_OLD));
    return push.rm(git, "refs/for/master").getChangeId();
  }

  private Change getChange(String changeId) throws Exception {
    return Iterables.getOnlyElement(db.changes()
        .byKey(new Change.Key(changeId)));
  }

  private PatchSet getCurrentPatchSet(String changeId) throws Exception {
    return db.patchSets()
        .get(getChange(changeId).currentPatchSetId());
  }

  private static byte[] toBytes(BinaryResult content) throws Exception {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    content.writeTo(os);
    return os.toByteArray();
  }
}
