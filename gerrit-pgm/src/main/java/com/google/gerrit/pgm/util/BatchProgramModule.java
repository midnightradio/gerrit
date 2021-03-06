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

package com.google.gerrit.pgm.util;

import static com.google.inject.Scopes.SINGLETON;

import com.google.common.cache.Cache;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.rules.PrologModule;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountByEmailCacheImpl;
import com.google.gerrit.server.account.AccountCacheImpl;
import com.google.gerrit.server.account.CapabilityControl;
import com.google.gerrit.server.account.GroupCacheImpl;
import com.google.gerrit.server.account.GroupIncludeCacheImpl;
import com.google.gerrit.server.cache.CacheRemovalListener;
import com.google.gerrit.server.cache.h2.DefaultCacheFactory;
import com.google.gerrit.server.change.ChangeKindCacheImpl;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.CanonicalWebUrlProvider;
import com.google.gerrit.server.config.FactoryModule;
import com.google.gerrit.server.git.ChangeCache;
import com.google.gerrit.server.git.TagCache;
import com.google.gerrit.server.group.GroupModule;
import com.google.gerrit.server.patch.PatchListCacheImpl;
import com.google.gerrit.server.project.AccessControlModule;
import com.google.gerrit.server.project.CommentLinkInfo;
import com.google.gerrit.server.project.CommentLinkProvider;
import com.google.gerrit.server.project.ProjectCacheImpl;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.project.SectionSortCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Providers;

import java.util.List;

/**
 * Module for programs that perform batch operations on a site.
 * <p>
 * Any program that requires this module likely also requires using
 * {@link ThreadLimiter} to limit the number of threads accessing the database
 * concurrently.
 */
public class BatchProgramModule extends FactoryModule {
  private final Module reviewDbModule;

  @Inject
  BatchProgramModule(PerThreadReviewDbModule reviewDbModule) {
    this.reviewDbModule = reviewDbModule;
  }

  @SuppressWarnings("rawtypes")
  @Override
  protected void configure() {
    install(reviewDbModule);
    install(PatchListCacheImpl.module());
    // Plugins are not loaded and we're just running through each change
    // once, so don't worry about cache removal.
    bind(new TypeLiteral<DynamicSet<CacheRemovalListener>>() {})
        .toInstance(DynamicSet.<CacheRemovalListener> emptySet());
    bind(new TypeLiteral<DynamicMap<Cache<?, ?>>>() {})
        .toInstance(DynamicMap.<Cache<?, ?>> emptyMap());
    bind(new TypeLiteral<List<CommentLinkInfo>>() {})
        .toProvider(CommentLinkProvider.class).in(SINGLETON);
    bind(String.class).annotatedWith(CanonicalWebUrl.class)
        .toProvider(CanonicalWebUrlProvider.class);
    bind(IdentifiedUser.class)
      .toProvider(Providers.<IdentifiedUser> of(null));
    bind(CurrentUser.class).to(IdentifiedUser.class);
    install(new AccessControlModule());
    install(new DefaultCacheFactory.Module());
    install(new GroupModule());
    install(new PrologModule());
    install(AccountByEmailCacheImpl.module());
    install(AccountCacheImpl.module());
    install(GroupCacheImpl.module());
    install(GroupIncludeCacheImpl.module());
    install(ProjectCacheImpl.module());
    install(SectionSortCache.module());
    install(ChangeKindCacheImpl.module());
    install(ChangeCache.module());
    install(TagCache.module());
    factory(CapabilityControl.Factory.class);
    factory(ChangeData.Factory.class);
    factory(ProjectState.Factory.class);
  }
}
