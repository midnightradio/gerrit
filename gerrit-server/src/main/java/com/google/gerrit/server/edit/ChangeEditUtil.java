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

package com.google.gerrit.server.edit;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.PatchSetInserter;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.util.TimeUtil;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.List;

/**
 * Utility functions to manipulate change edits.
 * <p>
 * This class contains methods to retrieve, publish and delete edits.
 * For changing edits see {@link ChangeEditModifier}.
 */
@Singleton
public class ChangeEditUtil {
  private final GitRepositoryManager gitManager;
  private final PatchSetInserter.Factory patchSetInserterFactory;
  private final ChangeControl.GenericFactory changeControlFactory;
  private final Provider<ReviewDb> db;
  private final Provider<IdentifiedUser> user;

  @Inject
  ChangeEditUtil(GitRepositoryManager gitManager,
      PatchSetInserter.Factory patchSetInserterFactory,
      ChangeControl.GenericFactory changeControlFactory,
      Provider<ReviewDb> db,
      Provider<IdentifiedUser> user) {
    this.gitManager = gitManager;
    this.patchSetInserterFactory = patchSetInserterFactory;
    this.changeControlFactory = changeControlFactory;
    this.db = db;
    this.user = user;
  }

  /**
   * Retrieve edits for a change and user. Max. one change edit can
   * exist per user and change.
   *
   * @param change
   * @return edit for this change for this user, if present.
   * @throws AuthException
   * @throws IOException
   */
  public Optional<ChangeEdit> byChange(Change change)
      throws AuthException, IOException {
    if (!user.get().isIdentifiedUser()) {
      throw new AuthException("Authentication required");
    }
    Repository repo = gitManager.openRepository(change.getProject());
    try {
      IdentifiedUser identifiedUser = (IdentifiedUser) user.get();
      Ref ref = repo.getRefDatabase().getRef(editRefName(
          identifiedUser.getAccountId(), change.getId()));
      if (ref == null) {
        return Optional.absent();
      }
      return Optional.of(new ChangeEdit(identifiedUser, change, ref));
    } finally {
      repo.close();
    }
  }

  /**
   * Promote change edit to patch set, by squashing the edit into
   * its parent.
   *
   * @param edit change edit to publish
   * @throws AuthException
   * @throws NoSuchChangeException
   * @throws IOException
   * @throws InvalidChangeOperationException
   * @throws OrmException
   * @throws ResourceConflictException
   */
  public void publish(ChangeEdit edit) throws AuthException,
      NoSuchChangeException, IOException, InvalidChangeOperationException,
      OrmException, ResourceConflictException {
    Change change = edit.getChange();
    Repository repo = gitManager.openRepository(change.getProject());
    try {
      RevWalk rw = new RevWalk(repo);
      ObjectInserter inserter = repo.newObjectInserter();
      try {
        RevCommit editCommit = rw.parseCommit(edit.getRef().getObjectId());
        if (editCommit == null) {
          throw new NoSuchChangeException(change.getId());
        }

        PatchSet basePatchSet = getBasePatchSet(edit, editCommit);
        if (!basePatchSet.getId().equals(change.currentPatchSetId())) {
          throw new ResourceConflictException(
              "only edit for current patch set can be published");
        }

        insertPatchSet(edit, change, repo, rw, basePatchSet,
            squashEdit(repo, rw, inserter, editCommit, basePatchSet));
      } finally {
        inserter.release();
        rw.release();
      }

      // TODO(davido): This should happen in the same BatchRefUpdate.
      deleteRef(repo, edit);
    } finally {
      repo.close();
    }
  }

  /**
   * Delete change edit.
   *
   * @param edit change edit to delete
   * @throws IOException
   */
  public void delete(ChangeEdit edit)
      throws IOException {
    Change change = edit.getChange();
    Repository repo = gitManager.openRepository(change.getProject());
    try {
      deleteRef(repo, edit);
    } finally {
      repo.close();
    }
  }

  /**
   * Retrieve base patch set the edit was created on.
   *
   * @param edit change edit to retrieve base patch set for
   * @return parent patch set of the edit
   * @throws IOException
   * @throws InvalidChangeOperationException
   */
  public PatchSet getBasePatchSet(ChangeEdit edit) throws IOException,
      InvalidChangeOperationException {
    Change change = edit.getChange();
    Repository repo = gitManager.openRepository(change.getProject());
    try {
      RevWalk rw = new RevWalk(repo);
      try {
        return getBasePatchSet(edit, rw.parseCommit(
            edit.getRef().getObjectId()));
      } finally {
        rw.release();
      }
    } finally {
      repo.close();
    }
  }

  /**
   * Retrieve base patch set the edit was created on.
   *
   * @param edit change edit to retrieve base patch set for
   * @param commit change edit commit
   * @return parent patch set of the edit
   * @throws IOException
   * @throws InvalidChangeOperationException
   */
  public PatchSet getBasePatchSet(ChangeEdit edit, RevCommit commit)
      throws IOException, InvalidChangeOperationException {
    if (commit.getParentCount() != 1) {
      throw new InvalidChangeOperationException(
          "change edit commit has multiple parents");
    }
    RevCommit parentCommit = commit.getParent(0);
    ObjectId rev = parentCommit.getId();
    RevId parentRev = new RevId(ObjectId.toString(rev));
    try {
      List<PatchSet> r = db.get().patchSets().byRevision(parentRev).toList();
      if (r.isEmpty()) {
        throw new InvalidChangeOperationException(String.format(
            "patch set %s change edit is based on doesn't exist",
            rev.abbreviate(8)));
      }
      if (r.size() > 1) {
        throw new InvalidChangeOperationException(String.format(
            "multiple patch sets for change edit parent %s",
            rev.abbreviate(8)));
      }
      PatchSet parentPatchSet = Iterables.getOnlyElement(r);
      if (!edit.getChange().getId().equals(
              parentPatchSet.getId().getParentKey())) {
        throw new InvalidChangeOperationException(String.format(
            "different change edit ID %d and its parent patch set %d",
            edit.getChange().getId().get(),
            parentPatchSet.getId().getParentKey().get()));
      }
      return parentPatchSet;
    } catch (OrmException e) {
      throw new IOException(e);
    }
  }

  /**
   * Returns reference for this change edit with sharded user and change number:
   * refs/users/UU/UUUU/edit-CCCC.
   *
   * @param accountId accout id
   * @param changeId change number
   * @return reference for this change edit
   */
  static String editRefName(Account.Id accountId, Change.Id changeId) {
    return String.format("%s/edit-%d",
        RefNames.refsUsers(accountId),
        changeId.get());
  }

  private RevCommit squashEdit(Repository repo, RevWalk rw,
      ObjectInserter inserter, RevCommit edit, PatchSet basePatchSet)
      throws IOException, ResourceConflictException {
    RevCommit parent = rw.parseCommit(ObjectId.fromString(
        basePatchSet.getRevision().get()));
    if (parent.getTree().equals(edit.getTree())) {
      throw new ResourceConflictException("identical tree");
    }
    return writeSquashedCommit(rw, inserter, parent, edit);
  }

  private void insertPatchSet(ChangeEdit edit, Change change,
      Repository repo, RevWalk rw, PatchSet basePatchSet, RevCommit squashed)
      throws NoSuchChangeException, InvalidChangeOperationException,
      OrmException, IOException {
    PatchSet ps = new PatchSet(
        ChangeUtil.nextPatchSetId(change.currentPatchSetId()));
    ps.setRevision(new RevId(ObjectId.toString(squashed)));
    ps.setUploader(edit.getUser().getAccountId());
    ps.setCreatedOn(TimeUtil.nowTs());

    PatchSetInserter insr =
        patchSetInserterFactory.create(repo, rw,
            changeControlFactory.controlFor(change, edit.getUser()),
            squashed);
    insr.setPatchSet(ps)
        .setMessage(
            String.format("Patch Set %d: Published edit on patch set %d",
                ps.getPatchSetId(),
                basePatchSet.getPatchSetId()))
        .insert();
  }

  private static void deleteRef(Repository repo, ChangeEdit edit)
      throws IOException {
    String refName = edit.getRefName();
    RefUpdate ru = repo.updateRef(refName, true);
    ru.setExpectedOldObjectId(edit.getRef().getObjectId());
    ru.setForceUpdate(true);
    RefUpdate.Result result = ru.delete();
    switch (result) {
      case FORCED:
      case NEW:
      case NO_CHANGE:
        break;
      default:
        throw new IOException(String.format("Failed to delete ref %s: %s",
            refName, result));
    }
  }

  private static RevCommit writeSquashedCommit(RevWalk rw,
      ObjectInserter inserter, RevCommit parent, RevCommit edit)
      throws IOException {
    CommitBuilder mergeCommit = new CommitBuilder();
    mergeCommit.setParentIds(parent.getParent(0));
    mergeCommit.setAuthor(parent.getAuthorIdent());
    mergeCommit.setMessage(parent.getFullMessage());
    mergeCommit.setCommitter(edit.getCommitterIdent());
    mergeCommit.setTreeId(edit.getTree());

    return rw.parseCommit(commit(inserter, mergeCommit));
  }

  private static ObjectId commit(ObjectInserter inserter,
      CommitBuilder mergeCommit) throws IOException {
    ObjectId id = inserter.insert(mergeCommit);
    inserter.flush();
    return id;
  }
}
