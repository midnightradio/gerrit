// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.server.notedb;

import static com.google.gerrit.server.project.Util.category;
import static com.google.gerrit.server.project.Util.value;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.PatchSetInfo;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.MetaDataUpdate;
import com.google.gerrit.server.util.TimeUtil;
import com.google.gerrit.testutil.FakeAccountCache;
import com.google.gerrit.testutil.InMemoryRepositoryManager;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeUtils.MillisProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

public class ChangeNotesTest {
  private static final TimeZone TZ =
      TimeZone.getTimeZone("America/Los_Angeles");

  private static final Account CHANGE_OWNER;
  private static final Account OTHER_ACCOUNT;
  static {
    CHANGE_OWNER = new Account(new Account.Id(1), TimeUtil.nowTs());
    CHANGE_OWNER.setFullName("Change Owner");
    CHANGE_OWNER.setPreferredEmail("change@owner.com");
    OTHER_ACCOUNT = new Account(new Account.Id(2), TimeUtil.nowTs());
    OTHER_ACCOUNT.setFullName("Other Account");
    OTHER_ACCOUNT.setPreferredEmail("other@account.com");
  }

  private static final LabelTypes LABEL_TYPES = new LabelTypes(ImmutableList.of(
      category("Verified",
        value(1, "Verified"),
        value(0, "No score"),
        value(-1, "Fails")),
      category("Code-Review",
        value(1, "Looks Good To Me"),
        value(0, "No score"),
        value(-1, "Do Not Submit"))));

  private Project.NameKey project;
  private InMemoryRepositoryManager repoManager;
  private InMemoryRepository repo;
  private FakeAccountCache accountCache;
  private volatile long clockStepMs;

  @Before
  public void setUp() throws Exception {
    project = new Project.NameKey("test-project");
    repoManager = new InMemoryRepositoryManager();
    repo = repoManager.createRepository(project);
    accountCache = new FakeAccountCache();
    accountCache.put(CHANGE_OWNER);
    accountCache.put(OTHER_ACCOUNT);
  }

  @Before
  public void setMillisProvider() {
    clockStepMs = MILLISECONDS.convert(1, SECONDS);
    final AtomicLong clockMs = new AtomicLong(
        MILLISECONDS.convert(ChangeUtil.SORT_KEY_EPOCH_MINS, MINUTES)
        + MILLISECONDS.convert(60, DAYS));

    DateTimeUtils.setCurrentMillisProvider(new MillisProvider() {
      @Override
      public long getMillis() {
        return clockMs.getAndAdd(clockStepMs);
      }
    });
  }

  @After
  public void resetMillisProvider() {
    DateTimeUtils.setCurrentMillisSystem();
  }

  @Test
  public void approvalsCommitFormat() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putApproval("Code-Review", (short) -1);
    update.putApproval("Verified", (short) 1);
    update.putReviewer(CHANGE_OWNER.getId(), ReviewerState.REVIEWER);
    update.putReviewer(OTHER_ACCOUNT.getId(), ReviewerState.CC);
    commit(update);
    assertEquals("refs/changes/01/1/meta", update.getRefName());

    RevWalk walk = new RevWalk(repo);
    try {
      RevCommit commit = walk.parseCommit(update.getRevision());
      walk.parseBody(commit);
      assertEquals("Update patch set 1\n"
          + "\n"
          + "Patch-Set: 1\n"
          + "Reviewer: Change Owner <1@gerrit>\n"
          + "CC: Other Account <2@gerrit>\n"
          + "Label: Verified=+1\n"
          + "Label: Code-Review=-1\n",
          commit.getFullMessage());

      PersonIdent author = commit.getAuthorIdent();
      assertEquals("Change Owner", author.getName());
      assertEquals("change@owner.com", author.getEmailAddress());
      assertEquals(new Date(c.getCreatedOn().getTime() + 1000),
          author.getWhen());
      assertEquals(TimeZone.getTimeZone("GMT-8:00"), author.getTimeZone());

      PersonIdent committer = commit.getCommitterIdent();
      assertEquals("Change Owner", committer.getName());
      assertEquals("1@gerrit", committer.getEmailAddress());
      assertEquals(author.getWhen(), committer.getWhen());
      assertEquals(author.getTimeZone(), committer.getTimeZone());
    } finally {
      walk.release();
    }
  }

  @Test
  public void approvalsOnePatchSet() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putApproval("Code-Review", (short) -1);
    update.putApproval("Verified", (short) 1);
    commit(update);

    ChangeNotes notes = newNotes(c);
    assertEquals(1, notes.getApprovals().keySet().size());
    List<PatchSetApproval> psas =
      notes.getApprovals().get(c.currentPatchSetId());
    assertEquals(2, psas.size());

    assertEquals(c.currentPatchSetId(), psas.get(0).getPatchSetId());
    assertEquals(1, psas.get(0).getAccountId().get());
    assertEquals("Verified", psas.get(0).getLabel());
    assertEquals((short) 1, psas.get(0).getValue());
    assertEquals(truncate(after(c, 1000)), psas.get(0).getGranted());

    assertEquals(c.currentPatchSetId(), psas.get(1).getPatchSetId());
    assertEquals(1, psas.get(1).getAccountId().get());
    assertEquals("Code-Review", psas.get(1).getLabel());
    assertEquals((short) -1, psas.get(1).getValue());
    assertEquals(psas.get(0).getGranted(), psas.get(1).getGranted());
  }

  @Test
  public void approvalsMultiplePatchSets() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putApproval("Code-Review", (short) -1);
    commit(update);
    PatchSet.Id ps1 = c.currentPatchSetId();

    incrementPatchSet(c);
    update = newUpdate(c, CHANGE_OWNER);
    update.putApproval("Code-Review", (short) 1);
    commit(update);
    PatchSet.Id ps2 = c.currentPatchSetId();

    ChangeNotes notes = newNotes(c);
    ListMultimap<PatchSet.Id, PatchSetApproval> psas = notes.getApprovals();
    assertEquals(2, notes.getApprovals().keySet().size());

    PatchSetApproval psa1 = Iterables.getOnlyElement(psas.get(ps1));
    assertEquals(ps1, psa1.getPatchSetId());
    assertEquals(1, psa1.getAccountId().get());
    assertEquals("Code-Review", psa1.getLabel());
    assertEquals((short) -1, psa1.getValue());
    assertEquals(truncate(after(c, 1000)), psa1.getGranted());

    PatchSetApproval psa2 = Iterables.getOnlyElement(psas.get(ps2));
    assertEquals(ps2, psa2.getPatchSetId());
    assertEquals(1, psa2.getAccountId().get());
    assertEquals("Code-Review", psa2.getLabel());
    assertEquals((short) +1, psa2.getValue());
    assertEquals(truncate(after(c, 2000)), psa2.getGranted());
  }

  @Test
  public void approvalsMultipleApprovals() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putApproval("Code-Review", (short) -1);
    commit(update);

    ChangeNotes notes = newNotes(c);
    PatchSetApproval psa = Iterables.getOnlyElement(
        notes.getApprovals().get(c.currentPatchSetId()));
    assertEquals("Code-Review", psa.getLabel());
    assertEquals((short) -1, psa.getValue());

    update = newUpdate(c, CHANGE_OWNER);
    update.putApproval("Code-Review", (short) 1);
    commit(update);

    notes = newNotes(c);
    psa = Iterables.getOnlyElement(
        notes.getApprovals().get(c.currentPatchSetId()));
    assertEquals("Code-Review", psa.getLabel());
    assertEquals((short) 1, psa.getValue());
  }

  @Test
  public void approvalsMultipleUsers() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putApproval("Code-Review", (short) -1);
    commit(update);

    update = newUpdate(c, OTHER_ACCOUNT);
    update.putApproval("Code-Review", (short) 1);
    commit(update);

    ChangeNotes notes = newNotes(c);
    assertEquals(1, notes.getApprovals().keySet().size());
    List<PatchSetApproval> psas =
      notes.getApprovals().get(c.currentPatchSetId());
    assertEquals(2, psas.size());

    assertEquals(c.currentPatchSetId(), psas.get(0).getPatchSetId());
    assertEquals(1, psas.get(0).getAccountId().get());
    assertEquals("Code-Review", psas.get(0).getLabel());
    assertEquals((short) -1, psas.get(0).getValue());
    assertEquals(truncate(after(c, 1000)), psas.get(0).getGranted());

    assertEquals(c.currentPatchSetId(), psas.get(1).getPatchSetId());
    assertEquals(2, psas.get(1).getAccountId().get());
    assertEquals("Code-Review", psas.get(1).getLabel());
    assertEquals((short) 1, psas.get(1).getValue());
    assertEquals(truncate(after(c, 2000)), psas.get(1).getGranted());
  }

  @Test
  public void multipleReviewers() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putReviewer(CHANGE_OWNER.getId(), ReviewerState.REVIEWER);
    update.putReviewer(OTHER_ACCOUNT.getId(), ReviewerState.REVIEWER);
    commit(update);

    ChangeNotes notes = newNotes(c);
    assertEquals(ImmutableSetMultimap.of(
          ReviewerState.REVIEWER, new Account.Id(1),
          ReviewerState.REVIEWER, new Account.Id(2)),
        notes.getReviewers());
  }

  @Test
  public void reviewerTypes() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putReviewer(CHANGE_OWNER.getId(), ReviewerState.REVIEWER);
    update.putReviewer(OTHER_ACCOUNT.getId(), ReviewerState.CC);
    commit(update);

    ChangeNotes notes = newNotes(c);
    assertEquals(ImmutableSetMultimap.of(
          ReviewerState.REVIEWER, new Account.Id(1),
          ReviewerState.CC, new Account.Id(2)),
        notes.getReviewers());
  }

  @Test
  public void oneReviewerMultipleTypes() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putReviewer(OTHER_ACCOUNT.getId(), ReviewerState.REVIEWER);
    commit(update);

    ChangeNotes notes = newNotes(c);
    assertEquals(ImmutableSetMultimap.of(
          ReviewerState.REVIEWER, new Account.Id(2)),
        notes.getReviewers());

    update = newUpdate(c, OTHER_ACCOUNT);
    update.putReviewer(OTHER_ACCOUNT.getId(), ReviewerState.CC);
    commit(update);

    notes = newNotes(c);
    assertEquals(ImmutableSetMultimap.of(
          ReviewerState.CC, new Account.Id(2)),
        notes.getReviewers());
  }

  @Test
  public void removeReviewer() throws Exception {
    Change c = newChange();
    ChangeUpdate update = newUpdate(c, CHANGE_OWNER);
    update.putReviewer(OTHER_ACCOUNT.getId(), ReviewerState.REVIEWER);
    commit(update);

    update = newUpdate(c, CHANGE_OWNER);
    update.putApproval("Code-Review", (short) 1);
    commit(update);

    update = newUpdate(c, OTHER_ACCOUNT);
    update.putApproval("Code-Review", (short) 1);
    commit(update);

    ChangeNotes notes = newNotes(c);
    List<PatchSetApproval> psas =
        notes.getApprovals().get(c.currentPatchSetId());
    assertEquals(2, psas.size());
    assertEquals(CHANGE_OWNER.getId(), psas.get(0).getAccountId());
    assertEquals(OTHER_ACCOUNT.getId(), psas.get(1).getAccountId());

    update = newUpdate(c, CHANGE_OWNER);
    update.removeReviewer(OTHER_ACCOUNT.getId());
    commit(update);

    notes = newNotes(c);
    psas = notes.getApprovals().get(c.currentPatchSetId());
    assertEquals(1, psas.size());
    assertEquals(CHANGE_OWNER.getId(), psas.get(0).getAccountId());
  }

  private Change newChange() {
    Change.Id changeId = new Change.Id(1);
    Change c = new Change(
        new Change.Key("Iabcd1234abcd1234abcd1234abcd1234abcd1234"),
        changeId,
        CHANGE_OWNER.getId(),
        new Branch.NameKey(project, "master"),
        TimeUtil.nowTs());
    incrementPatchSet(c);
    return c;
  }

  private ChangeUpdate newUpdate(Change c, Account account)
      throws ConfigInvalidException, IOException {
    return new ChangeUpdate(repoManager, accountCache, LABEL_TYPES, c, account,
        TimeUtil.nowTs(), TZ);
  }

  private ChangeNotes newNotes(Change c)
      throws ConfigInvalidException, IOException {
    return new ChangeNotes(repo, c);
  }

  private static void incrementPatchSet(Change change) {
    PatchSet.Id curr = change.currentPatchSetId();
    PatchSetInfo ps = new PatchSetInfo(new PatchSet.Id(
        change.getId(), curr != null ? curr.get() + 1 : 1));
    ps.setSubject("Change subject");
    change.setCurrentPatchSet(ps);
  }

  private static Timestamp truncate(Timestamp ts) {
    return new Timestamp((ts.getTime() / 1000) * 1000);
  }

  private static Timestamp after(Change c, long millis) {
    return new Timestamp(c.getCreatedOn().getTime() + millis);
  }

  private RevCommit commit(ChangeUpdate update) throws IOException {
    MetaDataUpdate md = new MetaDataUpdate(GitReferenceUpdated.DISABLED,
        project, repo);
    md.getCommitBuilder().setAuthor(new PersonIdent(
        update.getAccount().getFullName(),
        update.getAccount().getPreferredEmail(),
        update.getWhen(), TZ));
    return update.commit(md);
  }
}