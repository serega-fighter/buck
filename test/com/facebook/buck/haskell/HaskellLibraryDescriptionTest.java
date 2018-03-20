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

package com.facebook.buck.haskell;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HaskellLibraryDescriptionTest {

  @Test
  public void compilerFlags() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-compiler-flag";
    HaskellLibraryBuilder builder =
        new HaskellLibraryBuilder(target).setCompilerFlags(ImmutableList.of(flag));
    BuildRuleResolver resolver =
        new TestBuildRuleResolver(TargetGraphFactory.newInstance(builder.build()));
    HaskellLibrary library = builder.build(resolver);
    library.getCompileInput(
        HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    BuildTarget compileTarget =
        HaskellDescriptionUtils.getCompileBuildTarget(
            target, HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    HaskellCompileRule rule = resolver.getRuleWithType(compileTarget, HaskellCompileRule.class);
    assertThat(rule.getFlags(), Matchers.hasItem(flag));
  }

  @Test
  public void targetsAndOutputsAreDifferentBetweenLinkStyles() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver(TargetGraphFactory.newInstance());
    BuildTarget baseTarget = BuildTargetFactory.newInstance("//:rule");

    BuildRule staticLib =
        new HaskellLibraryBuilder(
                baseTarget.withFlavors(
                    CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                    HaskellLibraryDescription.Type.STATIC.getFlavor()))
            .build(resolver);
    BuildRule staticPicLib =
        new HaskellLibraryBuilder(
                baseTarget.withFlavors(
                    CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                    HaskellLibraryDescription.Type.STATIC_PIC.getFlavor()))
            .build(resolver);
    BuildRule sharedLib =
        new HaskellLibraryBuilder(
                baseTarget.withFlavors(
                    CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                    HaskellLibraryDescription.Type.SHARED.getFlavor()))
            .build(resolver);

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    ImmutableList<Path> outputs =
        ImmutableList.of(
                Preconditions.checkNotNull(staticLib.getSourcePathToOutput()),
                Preconditions.checkNotNull(staticPicLib.getSourcePathToOutput()),
                Preconditions.checkNotNull(sharedLib.getSourcePathToOutput()))
            .stream()
            .map(pathResolver::getRelativePath)
            .collect(ImmutableList.toImmutableList());
    assertThat(outputs.size(), Matchers.equalTo(ImmutableSet.copyOf(outputs).size()));

    ImmutableList<BuildTarget> targets =
        ImmutableList.of(
            staticLib.getBuildTarget(), staticPicLib.getBuildTarget(), sharedLib.getBuildTarget());
    assertThat(targets.size(), Matchers.equalTo(ImmutableSet.copyOf(targets).size()));
  }

  @Test
  public void linkWhole() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    HaskellLibraryBuilder builder = new HaskellLibraryBuilder(target).setLinkWhole(true);
    BuildRuleResolver resolver =
        new TestBuildRuleResolver(TargetGraphFactory.newInstance(builder.build()));
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    HaskellLibrary library = builder.build(resolver);

    // Lookup the link whole flags.
    Linker linker = CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(resolver);
    ImmutableList<String> linkWholeFlags =
        FluentIterable.from(linker.linkWhole(StringArg.of("sentinel")))
            .transformAndConcat((input) -> Arg.stringifyList(input, pathResolver))
            .filter(Predicates.not("sentinel"::equals))
            .toList();

    // Test static dep type.
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, resolver);
    assertThat(
        Arg.stringify(staticInput.getArgs(), pathResolver),
        hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()])));

    // Test static-pic dep type.
    NativeLinkableInput staticPicInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC, resolver);
    assertThat(
        Arg.stringify(staticPicInput.getArgs(), pathResolver),
        hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()])));

    // Test shared dep type.
    NativeLinkableInput sharedInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, resolver);
    assertThat(
        Arg.stringify(sharedInput.getArgs(), pathResolver),
        not(hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()]))));
  }

  @Test
  public void preferredLinkage() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver(TargetGraphFactory.newInstance());

    // Test default value.
    HaskellLibrary defaultLib =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:default")).build(resolver);
    assertThat(
        defaultLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        Matchers.is(NativeLinkable.Linkage.ANY));

    // Test `ANY` value.
    HaskellLibrary anyLib =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:any"))
            .setPreferredLinkage(NativeLinkable.Linkage.ANY)
            .build(resolver);
    assertThat(
        anyLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        Matchers.is(NativeLinkable.Linkage.ANY));

    // Test `STATIC` value.
    HaskellLibrary staticLib =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:static"))
            .setPreferredLinkage(NativeLinkable.Linkage.STATIC)
            .build(resolver);
    assertThat(
        staticLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        Matchers.is(NativeLinkable.Linkage.STATIC));

    // Test `SHARED` value.
    HaskellLibrary sharedLib =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:shared"))
            .setPreferredLinkage(NativeLinkable.Linkage.SHARED)
            .build(resolver);
    assertThat(
        sharedLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        Matchers.is(NativeLinkable.Linkage.SHARED));
  }

  @Test
  public void thinArchivesPropagatesDepFromObjects() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxBuckConfig cxxBuckConfig =
        new CxxBuckConfig(
            FakeBuckConfig.builder().setSections("[cxx]", "archive_contents=thin").build());
    HaskellLibraryBuilder builder =
        new HaskellLibraryBuilder(
                target,
                HaskellTestUtils.DEFAULT_PLATFORM,
                HaskellTestUtils.DEFAULT_PLATFORMS,
                cxxBuckConfig)
            .setSrcs(
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(FakeSourcePath.of("Test.hs"))))
            .setLinkWhole(true);
    BuildRuleResolver resolver =
        new TestBuildRuleResolver(TargetGraphFactory.newInstance(builder.build()));
    HaskellLibrary library = builder.build(resolver);

    // Test static dep type.
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, resolver);
    assertThat(
        FluentIterable.from(staticInput.getArgs())
            .transformAndConcat(
                arg -> BuildableSupport.getDepsCollection(arg, new SourcePathRuleFinder(resolver)))
            .transform(BuildRule::getBuildTarget)
            .toList(),
        Matchers.hasItem(
            HaskellDescriptionUtils.getCompileBuildTarget(
                library.getBuildTarget(),
                HaskellTestUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.STATIC,
                false)));
  }

  @Test
  public void platformDeps() {
    HaskellLibraryBuilder depABuilder =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:depA"));
    HaskellLibraryBuilder depBBuilder =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:depB"));
    HaskellLibraryBuilder ruleBuilder =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString(),
                            Pattern.LITERAL),
                        ImmutableSortedSet.of(depABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(depBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            depABuilder.build(), depBBuilder.build(), ruleBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    HaskellLibrary depA = (HaskellLibrary) resolver.requireRule(depABuilder.getTarget());
    HaskellLibrary depB = (HaskellLibrary) resolver.requireRule(depBBuilder.getTarget());
    HaskellLibrary rule = (HaskellLibrary) resolver.requireRule(ruleBuilder.getTarget());
    assertThat(
        rule.getCompileDeps(HaskellTestUtils.DEFAULT_PLATFORM),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDepsForPlatform(
                CxxPlatformUtils.DEFAULT_PLATFORM, resolver)),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
    assertThat(
        rule.getCxxPreprocessorDeps(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
  }
}
