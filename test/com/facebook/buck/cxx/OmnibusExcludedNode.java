/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.rules.BuildRuleResolver;

/** A node that is always excluded from omnibus linking. */
class OmnibusExcludedNode extends OmnibusNode {

  public OmnibusExcludedNode(String target, Iterable<? extends NativeLinkable> deps) {
    super(target, deps);
  }

  public OmnibusExcludedNode(String target) {
    super(target);
  }

  @Override
  public boolean supportsOmnibusLinking(CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return false;
  }
}
