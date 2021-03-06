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

package com.google.gerrit.pgm.init;

import static com.google.gerrit.pgm.init.InitUtil.username;

import com.google.gerrit.pgm.init.api.Section;


public class OracleInitializer implements DatabaseConfigInitializer {

  @Override
  public void initConfig(Section databaseSection) {
    final String defPort = "1521";
    databaseSection.string("Server hostname", "hostname", "localhost");
    databaseSection.string("Server port", "port", defPort, false);
    databaseSection.string("Instance name", "instance", "xe");
    databaseSection.string("Database username", "username", username());
    databaseSection.password("username", "password");
  }
}
