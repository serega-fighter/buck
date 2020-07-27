/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.ocaml;

import static com.facebook.buck.features.ocaml.OcamlRuleBuilder.createOcamlLinkTarget;
import static com.facebook.buck.features.ocaml.OcamlRuleBuilder.createStaticLibraryBuildTarget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.CxxSourceRuleFactoryHelper;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class OCamlIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void checkOcamlIsConfigured() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "ocaml", tmp);
    workspace.setUp();

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    Config rawConfig = Configs.createDefaultConfig(filesystem.getRootPath().getPath());

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(rawConfig.getRawConfig())
            .build();

    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(
                    CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM,
                    CxxPlatformUtils.DEFAULT_PLATFORMS))
            .build();

    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
    ExecutableFinder executableFinder = new ExecutableFinder();
    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            EnvVariablesProvider.getSystemEnv(),
            buckConfig,
            new FakeProjectFilesystem(),
            processExecutor,
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    OcamlToolchainFactory factory = new OcamlToolchainFactory();
    Optional<OcamlToolchain> toolchain =
        factory.createToolchain(
            toolchainProvider, toolchainCreationContext, UnconfiguredTargetConfiguration.INSTANCE);

    OcamlPlatform ocamlPlatform =
        toolchain.orElseThrow(AssertionError::new).getDefaultOcamlPlatform();

    BuildRuleResolver resolver = new TestActionGraphBuilder();
    try {
      ocamlPlatform
          .getOcamlCompiler()
          .resolve(resolver, UnconfiguredTargetConfiguration.INSTANCE)
          .getCommandPrefix(resolver.getSourcePathResolver());
    } catch (HumanReadableException e) {
      assumeNoException(e);
    }
  }

  @Test
  public void testHelloOcamlBuild() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//hello_ocaml:hello_ocaml");
    BuildTarget binary = createOcamlLinkTarget(target);
    BuildTarget lib = BuildTargetFactory.newInstance("//hello_ocaml:ocamllib");
    BuildTarget staticLib =
        createStaticLibraryBuildTarget(lib).withAppendedFlavors(DefaultCxxPlatforms.FLAVOR);
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary, lib, staticLib);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(staticLib);

    workspace.resetBuildLogFile();

    // Check that running a build again results in no builds since everything is up to
    // date.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(binary, target), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binary);
    buildLog.assertTargetHadMatchingRuleKey(target);

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("hello_ocaml/amodule.ml", "v2", "v3");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetHadMatchingRuleKey(staticLib);

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("hello_ocaml/ocamllib/m1.ml", "print me", "print Me");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(staticLib);

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("hello_ocaml/BUCK", "#INSERT_POINT", "'ocamllib/dummy.ml',");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(staticLib);

    workspace.resetBuildLogFile();

    BuildTarget lib1 = BuildTargetFactory.newInstance("//hello_ocaml:ocamllib1");
    BuildTarget staticLib1 =
        createStaticLibraryBuildTarget(lib1).withAppendedFlavors(DefaultCxxPlatforms.FLAVOR);
    ImmutableSet<BuildTarget> targets1 = ImmutableSet.of(target, binary, lib1, staticLib1);
    // We rebuild if lib name changes
    workspace.replaceFileContents(
        "hello_ocaml/BUCK", "name = \"ocamllib\"", "name = \"ocamllib1\"");
    workspace.replaceFileContents("hello_ocaml/BUCK", ":ocamllib", ":ocamllib1");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets1));

    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(staticLib1);
  }

  @Test
  public void testNativePlugin() throws Exception {
    // Build the plugin
    BuildTarget pluginTarget =
        BuildTargetFactory.newInstance("//ocaml_native_plugin:plugin#default");
    workspace.runBuckCommand("build", pluginTarget.toString()).assertSuccess();

    // Also build a test binary that we'll use to verify that the .cmxs file
    // works
    BuildTarget binTarget = BuildTargetFactory.newInstance("//ocaml_native_plugin:tester");
    workspace.runBuckCommand("build", binTarget.toString()).assertSuccess();

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    AbsPath basePath = filesystem.getRootPath().toRealPath();

    Path testerExecutableFile =
        basePath.resolve(BuildTargetPaths.getGenPath(filesystem, binTarget, "%s/tester")).getPath();

    Path pluginCmxsFile =
        basePath
            .resolve(BuildTargetPaths.getGenPath(filesystem, pluginTarget, "%s/libplugin.cmxs"))
            .getPath();

    // Run `./tester /path/to/plugin.cmxs`
    String out =
        workspace
            .runCommand(testerExecutableFile.toString(), pluginCmxsFile.toString())
            .getStdout()
            .get();

    assertEquals("it works!\n", out);
  }

  @Test
  public void testLexAndYaccBuild() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//calc:calc");
    BuildTarget binary = createOcamlLinkTarget(target);

    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(targets, buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);

    workspace.resetBuildLogFile();

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(binary, target), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binary);
    buildLog.assertTargetHadMatchingRuleKey(target);

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("calc/lexer.mll", "The type token", "the type token");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(targets, buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("calc/parser.mly", "the entry point", "The entry point");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(targets, buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);
  }

  @Test
  public void testCInteropBuild() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//ctest:ctest");
    BuildTarget binary = createOcamlLinkTarget(target);
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target);

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(binary, target), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binary);
    buildLog.assertTargetHadMatchingRuleKey(target);

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/ctest.c", "NATIVE PLUS", "Native Plus");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/BUCK", "#INSERTION_POINT", "compiler_flags=['-noassert']");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);

    workspace.resetBuildLogFile();
    workspace.replaceFileContents(
        "ctest/BUCK", "compiler_flags=['-noassert']", "compiler_flags=[]");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/BUCK", "compiler_flags=[]", "compiler_flags=[]");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(binary, target), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binary);
    buildLog.assertTargetHadMatchingRuleKey(target);
  }

  @Test
  public void testSimpleBuildWithLib() {
    BuildTarget target = BuildTargetFactory.newInstance("//:plus");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
  }

  @Test
  public void testRootBuildTarget() {
    BuildTarget target = BuildTargetFactory.newInstance("//:main");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
  }

  /**
   * Manually compiles a library to be used via prebuilt_ocaml_library
   *
   * @throws IOException
   * @throws InterruptedException
   */
  private void setUpPrebuiltLibraryBytecodeOnly(ProjectWorkspace workspace)
      throws IOException, InterruptedException {
    Path ocamlc =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlc"), EnvVariablesProvider.getSystemEnv());

    ProcessExecutor.Result result;

    // Compile .cma (bytecode archive)
    result =
        workspace.runCommand(
            ocamlc.toString(),
            "-a",
            "-o",
            "prebuilt_ocaml_library/bytecode_only/lib/plus.cma",
            "prebuilt_ocaml_library/bytecode_only/src/plus.ml");
    assertEquals(0, result.getExitCode());

    // Copy compiled header
    workspace.move(
        "prebuilt_ocaml_library/bytecode_only/src/plus.cmi",
        "prebuilt_ocaml_library/bytecode_only/lib/plus.cmi");
  }

  @Test
  public void testPrebuiltLibraryBytecodeOnly() throws IOException, InterruptedException {
    setUpPrebuiltLibraryBytecodeOnly(workspace);

    BuildTarget target = BuildTargetFactory.newInstance("//prebuilt_ocaml_library:bytecode_only");
    BuildTarget binary = createOcamlLinkTarget(target);
    BuildTarget bytecode = OcamlBuildRulesGenerator.addBytecodeFlavor(binary);
    BuildTarget libplus =
        BuildTargetFactory.newInstance("//prebuilt_ocaml_library/bytecode_only/lib:plus");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, bytecode, libplus);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    assertFalse(buildLog.getAllTargets().contains(binary));
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(bytecode);
  }

  /**
   * Manually compiles a library to be used via prebuilt_ocaml_library
   *
   * @throws IOException
   * @throws InterruptedException
   */
  private void setUpPrebuiltLibraryWithC(ProjectWorkspace workspace)
      throws IOException, InterruptedException {
    Path ocamlc =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlc"), EnvVariablesProvider.getSystemEnv());
    Path ocamlopt =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlopt"), EnvVariablesProvider.getSystemEnv());
    Path ocamlmklib =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlmklib"), EnvVariablesProvider.getSystemEnv());

    ProcessExecutor.Result result;

    // Compile stubs .o
    result =
        workspace.runCommand(
            ocamlc.toString(),
            "-o",
            "plus_stubs.o",
            "prebuilt_ocaml_library/c_extension/src/plus_stubs.c");
    assertEquals(0, result.getExitCode());
    workspace.move("plus_stubs.o", "prebuilt_ocaml_library/c_extension/lib/plus_stubs.o");

    // Compile stubs lib
    result =
        workspace.runCommand(
            ocamlmklib.toString(),
            "-o",
            "prebuilt_ocaml_library/c_extension/lib/plus_stubs",
            "prebuilt_ocaml_library/c_extension/lib/plus_stubs.o");
    assertEquals(0, result.getExitCode());

    // Compile .cmxa (native archive)
    result =
        workspace.runCommand(
            ocamlopt.toString(),
            "-a",
            "-o",
            "prebuilt_ocaml_library/c_extension/lib/plus.cmxa",
            "prebuilt_ocaml_library/c_extension/src/plus.ml");
    assertEquals(0, result.getExitCode());

    // Compile .cma (bytecode archive)
    result =
        workspace.runCommand(
            ocamlc.toString(),
            "-a",
            "-o",
            "prebuilt_ocaml_library/c_extension/lib/plus.cma",
            "prebuilt_ocaml_library/c_extension/src/plus.ml",
            "prebuilt_ocaml_library/c_extension/src/plus_stubs.c");
    assertEquals(0, result.getExitCode());

    // Copy compiled header
    workspace.move(
        "prebuilt_ocaml_library/c_extension/src/plus.cmi",
        "prebuilt_ocaml_library/c_extension/lib/plus.cmi");
  }

  @Test
  public void testPrebuiltLibraryWithC() throws IOException, InterruptedException {
    setUpPrebuiltLibraryWithC(workspace);

    BuildTarget target = BuildTargetFactory.newInstance("//prebuilt_ocaml_library:c_extension");
    BuildTarget binary = createOcamlLinkTarget(target);
    BuildTarget bytecode = OcamlBuildRulesGenerator.addBytecodeFlavor(binary);
    BuildTarget libplus =
        BuildTargetFactory.newInstance("//prebuilt_ocaml_library/c_extension/lib:plus");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary, bytecode, libplus);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget t : targets) {
      assertTrue(
          String.format("Expected %s to be built", t.toString()),
          buildLog.getAllTargets().contains(t));
    }
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);

    /* TODO: caching is disabled for ocaml. ideally it would not rebuild
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    for (BuildTarget t : targets) {
      assertTrue(
          String.format("Expected %s to be built", t.toString()),
          buildLog.getAllTargets().contains(t));
    }
    buildLog.assertTargetHadMatchingRuleKey(target);
    buildLog.assertTargetHadMatchingRuleKey(binary);
    */
  }

  /**
   * Manually compiles a library to be used via prebuilt_ocaml_library
   *
   * @throws IOException
   * @throws InterruptedException
   */
  private void setUpPrebuiltLibraryBytecodeAndNative(ProjectWorkspace workspace)
      throws IOException, InterruptedException {
    Path ocamlc =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlc"), EnvVariablesProvider.getSystemEnv());
    Path ocamlopt =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlopt"), EnvVariablesProvider.getSystemEnv());

    ProcessExecutor.Result result;

    // Compile .cmxa (native archive)
    result =
        workspace.runCommand(
            ocamlopt.toString(),
            "-a",
            "-o",
            "prebuilt_ocaml_library/bytecode_and_native/lib/plus.cmxa",
            "prebuilt_ocaml_library/bytecode_and_native/src/plus.ml");
    assertEquals(0, result.getExitCode());

    // Compile .cma (bytecode archive)
    result =
        workspace.runCommand(
            ocamlc.toString(),
            "-a",
            "-o",
            "prebuilt_ocaml_library/bytecode_and_native/lib/plus.cma",
            "prebuilt_ocaml_library/bytecode_and_native/src/plus.ml");
    assertEquals(0, result.getExitCode());

    // Copy compiled header
    workspace.move(
        "prebuilt_ocaml_library/bytecode_and_native/src/plus.cmi",
        "prebuilt_ocaml_library/bytecode_and_native/lib/plus.cmi");
  }

  @Test
  public void testPrebuiltLibraryWithBytecodeAndNative() throws IOException, InterruptedException {
    setUpPrebuiltLibraryBytecodeAndNative(workspace);

    BuildTarget target =
        BuildTargetFactory.newInstance("//prebuilt_ocaml_library:bytecode_and_native");
    BuildTarget binary = createOcamlLinkTarget(target);
    BuildTarget bytecode = OcamlBuildRulesGenerator.addBytecodeFlavor(binary);
    BuildTarget libplus =
        BuildTargetFactory.newInstance("//prebuilt_ocaml_library/bytecode_and_native/lib:plus");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary, bytecode, libplus);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget t : targets) {
      assertTrue(
          String.format("Expected %s to be built", t.toString()),
          buildLog.getAllTargets().contains(t));
    }
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);

    /* TODO: caching is disabled for ocaml. ideally it would not rebuild
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    for (BuildTarget t : targets) {
      assertTrue(
          String.format("Expected %s to be built", t.toString()),
          buildLog.getAllTargets().contains(t));
    }
    buildLog.assertTargetHadMatchingRuleKey(target);
    buildLog.assertTargetHadMatchingRuleKey(binary);
    */
  }

  @Test
  public void testCppLibraryDependency() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//clib:clib");
    BuildTarget binary = createOcamlLinkTarget(target);
    BuildTarget libplus = BuildTargetFactory.newInstance("//clib:plus");
    BuildTarget libplusStatic =
        createStaticLibraryBuildTarget(libplus).withAppendedFlavors(DefaultCxxPlatforms.FLAVOR);
    BuildTarget cclib = BuildTargetFactory.newInstance("//clib:cc");

    BuckConfig buckConfig = FakeBuckConfig.empty();
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(buckConfig), DownwardApiConfig.of(buckConfig));
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), cclib, cxxPlatform);
    BuildTarget cclibbin =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            cclib, cxxPlatform.getFlavor(), PicType.PDC);
    String sourceName = "cc/cc.cpp";
    BuildTarget ccObj = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            cclib, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget exportedHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            cclib,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(binary);
    buildLog.assertTargetBuiltLocally(libplusStatic);
    buildLog.assertTargetBuiltLocally(cclibbin);
    buildLog.assertTargetBuiltLocally(ccObj);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(exportedHeaderSymlinkTreeTarget);

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingRuleKey(binary);
    buildLog.assertTargetHadMatchingRuleKey(target);

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("clib/cc/cc.cpp", "Hi there", "hi there");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(binary);
    buildLog.assertTargetBuiltLocally(libplusStatic);
    buildLog.assertTargetBuiltLocally(cclibbin);
    buildLog.assertTargetBuiltLocally(ccObj);
  }

  @Test
  public void testConfigWarningsFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "config_warnings_flags", tmp.newFolder());
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:unused_var");
    BuildTarget binary = createOcamlLinkTarget(target);

    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    workspace.runBuckCommand("build", target.toString()).assertFailure();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetFailed(target.toString());
    buildLog.assertTargetFailed(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents(".buckconfig", "warnings_flags=+a", "");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);
  }

  @Test
  public void testConfigInteropIncludes() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "config_interop_includes", tmp.newFolder());
    workspace.setUp();

    Path ocamlc =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlc"), EnvVariablesProvider.getSystemEnv());

    ProcessExecutor.Result result = workspace.runCommand(ocamlc.toString(), "-where");
    assertEquals(0, result.getExitCode());
    String stdlibPath = result.getStdout().get();

    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildTarget binary = createOcamlLinkTarget(target);

    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    // Points somewhere with no stdlib in it, so fails to find Pervasives
    workspace.runBuckCommand("build", target.toString()).assertFailure();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(buildLog.getAllTargets(), Matchers.hasItems(targets.toArray(new BuildTarget[0])));
    buildLog.assertTargetFailed(target.toString());
    buildLog.assertTargetFailed(binary.toString());

    workspace.resetBuildLogFile();

    // Point to the real stdlib (from `ocamlc -where`)
    workspace.replaceFileContents(
        ".buckconfig", "interop.includes=lib", "interop.includes=" + stdlibPath);
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);

    workspace.resetBuildLogFile();

    // Remove the config, should default to a valid place
    workspace.replaceFileContents(".buckconfig", "interop.includes=" + stdlibPath, "");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetBuiltLocally(binary);
  }

  @Test
  public void testGenruleDependency() throws IOException {
    BuildTarget binary = BuildTargetFactory.newInstance("//generated:binary");
    BuildTarget generated = BuildTargetFactory.newInstance("//generated:generated");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(binary, generated);

    // Build the binary.
    workspace.runBuckCommand("build", binary.toString()).assertSuccess();

    // Make sure the generated target is built as well.
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(binary);
  }

  @Test
  public void testCompilerFlagsDependency() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "compiler_flag_macros", tmp.newFolder());
    workspace.setUp();

    String ocamlVersion = this.getOcamlVersion(workspace);
    assumeTrue("Installed ocaml is too old for this test", "4.02.0".compareTo(ocamlVersion) <= 0);

    BuildTarget binary = BuildTargetFactory.newInstance("//:main");
    BuildTarget lib = BuildTargetFactory.newInstance("//:lib");
    BuildTarget helper = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(binary, lib, helper);

    // Build the binary.
    workspace.runBuckCommand("build", binary.toString()).assertSuccess();

    // Make sure the helper target is built as well.
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(binary);

    // Make sure the ppx flag worked
    String out = workspace.runBuckCommand("run", binary.toString()).getStdout();
    assertEquals("42!\n", out);
  }

  @Test
  public void testOcamlDepFlagMacros() throws IOException {
    BuildTarget binary = BuildTargetFactory.newInstance("//ocamldep_flags:main");
    BuildTarget lib = BuildTargetFactory.newInstance("//ocamldep_flags:code");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(binary, lib);

    workspace.runBuckCommand("build", binary.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(binary);

    // Make sure the ppx flag worked
    String out = workspace.runBuckCommand("run", binary.toString()).getStdout();
    assertEquals("142!\n", out);
  }

  private String getOcamlVersion(ProjectWorkspace workspace)
      throws IOException, InterruptedException {
    Path ocamlc =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlc"), EnvVariablesProvider.getSystemEnv());

    ProcessExecutor.Result result = workspace.runCommand(ocamlc.toString(), "-version");
    assertEquals(0, result.getExitCode());
    return result.getStdout().get();
  }
}
