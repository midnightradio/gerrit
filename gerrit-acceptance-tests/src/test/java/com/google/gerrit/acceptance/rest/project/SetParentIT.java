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

package com.google.gerrit.acceptance.rest.project;

import static com.google.gerrit.acceptance.GitUtil.createProject;
import static org.junit.Assert.assertEquals;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.project.SetParent;
import com.google.inject.Inject;

import com.jcraft.jsch.JSchException;

import org.apache.http.HttpStatus;
import org.junit.Test;

import java.io.IOException;

public class SetParentIT extends AbstractDaemonTest {

  @Inject
  private AllProjectsNameProvider allProjects;

  @Test
  public void setParent_Forbidden() throws IOException, JSchException {
    String parent = "parent";
    createProject(sshSession, parent, null, true);
    RestResponse r =
        userSession.put("/projects/" + project.get() + "/parent",
            newParentInput(parent));
    assertEquals(HttpStatus.SC_FORBIDDEN, r.getStatusCode());
    r.consume();
  }

  @Test
  public void setParent() throws IOException, JSchException {
    String parent = "parent";
    createProject(sshSession, parent, null, true);
    RestResponse r =
        adminSession.put("/projects/" + project.get() + "/parent",
            newParentInput(parent));
    assertEquals(HttpStatus.SC_OK, r.getStatusCode());
    r.consume();

    r = adminSession.get("/projects/" + project.get() + "/parent");
    assertEquals(HttpStatus.SC_OK, r.getStatusCode());
    String newParent =
        newGson().fromJson(r.getReader(), String.class);
    assertEquals(parent, newParent);
    r.consume();
  }

  @Test
  public void setParentForAllProjects_Conflict() throws IOException {
    RestResponse r =
        adminSession.put("/projects/" + allProjects.get() + "/parent",
            newParentInput(project.get()));
    assertEquals(HttpStatus.SC_CONFLICT, r.getStatusCode());
    r.consume();
  }

  @Test
  public void setInvalidParent_Conflict() throws IOException, JSchException {
    RestResponse r =
        adminSession.put("/projects/" + project.get() + "/parent",
            newParentInput(project.get()));
    assertEquals(HttpStatus.SC_CONFLICT, r.getStatusCode());
    r.consume();

    String child = "child";
    createProject(sshSession, child, project, true);
    r = adminSession.put("/projects/" + project.get() + "/parent",
           newParentInput(child));
    assertEquals(HttpStatus.SC_CONFLICT, r.getStatusCode());
    r.consume();

    String grandchild = "grandchild";
    createProject(sshSession, grandchild, new Project.NameKey(child), true);
    r = adminSession.put("/projects/" + project.get() + "/parent",
           newParentInput(grandchild));
    assertEquals(HttpStatus.SC_CONFLICT, r.getStatusCode());
    r.consume();
  }

  @Test
  public void setNonExistingParent_UnprocessibleEntity() throws IOException {
    RestResponse r =
        adminSession.put("/projects/" + project.get() + "/parent",
            newParentInput("non-existing"));
    assertEquals(HttpStatus.SC_UNPROCESSABLE_ENTITY, r.getStatusCode());
    r.consume();
  }

  SetParent.Input newParentInput(String project) {
    SetParent.Input in = new SetParent.Input();
    in.parent = project;
    return in;
  }
}
