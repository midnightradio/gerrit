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

package com.google.gerrit.acceptance.rest.change;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.testutil.ConfigSuite;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Config;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeUtils.MillisProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(ConfigSuite.class)
public class ChangeMessagesIT extends AbstractDaemonTest {
  private String systemTimeZone;
  private volatile long clockStepMs;

  @ConfigSuite.Config
  public static Config noteDbEnabled() {
    Config cfg = new Config();
    cfg.setBoolean("notedb", null, "write", true);
    cfg.setBoolean("notedb", "changeMessages", "read", true);
    return cfg;
  }

  @Before
  public void setTimeForTesting() {
    systemTimeZone = System.setProperty("user.timezone", "US/Eastern");
    clockStepMs = MILLISECONDS.convert(1, SECONDS);
    final AtomicLong clockMs = new AtomicLong(
        new DateTime(2009, 9, 30, 17, 0, 0).getMillis());

    DateTimeUtils.setCurrentMillisProvider(new MillisProvider() {
      @Override
      public long getMillis() {
        return clockMs.getAndAdd(clockStepMs);
      }
    });
  }

  @After
  public void resetTime() {
    DateTimeUtils.setCurrentMillisSystem();
    System.setProperty("user.timezone", systemTimeZone);
  }

  @Test
  public void messagesNotReturnedByDefault() throws Exception {
    String changeId = createChange().getChangeId();
    postMessage(changeId, "Some nits need to be fixed.");
    ChangeInfo c = info(changeId);
    assertNull(c.messages);
  }

  @Test
  public void defaultMessage() throws GitAPIException, IOException,
      RestApiException {
    String changeId = createChange().getChangeId();
    ChangeInfo c = get(changeId);
    assertNotNull(c.messages);
    assertEquals(1, c.messages.size());
    assertEquals("Uploaded patch set 1.", c.messages.iterator().next().message);
  }

  @Test
  public void messagesReturnedInChronologicalOrder() throws Exception {
    String changeId = createChange().getChangeId();
    String firstMessage = "Some nits need to be fixed.";
    postMessage(changeId, firstMessage);
    String secondMessage = "I like this feature.";
    postMessage(changeId, secondMessage);
    ChangeInfo c = get(changeId);
    assertNotNull(c.messages);
    assertEquals(3, c.messages.size());
    Iterator<ChangeMessageInfo> it = c.messages.iterator();
    assertEquals("Uploaded patch set 1.", it.next().message);
    assertMessage(firstMessage, it.next().message);
    assertMessage(secondMessage, it.next().message);
  }

  private void assertMessage(String expected, String actual) {
    assertEquals("Patch Set 1:\n\n" + expected, actual);
  }

  private void postMessage(String changeId, String msg) throws Exception {
    ReviewInput in = new ReviewInput();
    in.message = msg;
    gApi.changes().id(changeId).current().review(in);
  }
}
