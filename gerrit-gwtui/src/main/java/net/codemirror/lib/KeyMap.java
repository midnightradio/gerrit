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

package net.codemirror.lib;

import com.google.gwt.core.client.JavaScriptObject;

/** Object that associates a key or key combination with a handler. */
public class KeyMap extends JavaScriptObject {
  public static KeyMap create() {
    return createObject().cast();
  }

  public final native KeyMap on(String key, Runnable thunk) /*-{
    this[key] = function() { $entry(thunk.@java.lang.Runnable::run()()); };
    return this;
  }-*/;

  public final native KeyMap on(String key, boolean b) /*-{
    this[key] = b;
    return this;
  }-*/;

  public final native KeyMap remove(String key) /*-{ delete this[key]; }-*/;

  protected KeyMap() {
  }
}
