// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository.skylark;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.bazel.debug.WorkspaceRuleEvent;
import com.google.devtools.build.lib.bazel.repository.DecompressorDescriptor;
import com.google.devtools.build.lib.bazel.repository.DecompressorValue;
import com.google.devtools.build.lib.bazel.repository.PatchUtil;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache.KeyType;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.bazel.repository.downloader.HttpUtils;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler.FetchProgress;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.rules.repository.WorkspaceAttributeMapper;
import com.google.devtools.build.lib.runtime.ProcessWrapperUtil;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor.ExecutionResult;
import com.google.devtools.build.lib.skylarkbuildapi.repository.SkylarkRepositoryContextApi;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Location;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.Sequence;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.OsUtils;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import difflib.PatchFailedException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Starlark API for the repository_rule's context. */
public class SkylarkRepositoryContext
    implements SkylarkRepositoryContextApi<RepositoryFunctionException> {
  private static final ImmutableList<String> WHITELISTED_REPOS_FOR_FLAG_ENABLED =
      ImmutableList.of("@rules_cc", "@bazel_tools");
  private static final ImmutableList<String> WHITELISTED_PATHS_FOR_FLAG_ENABLED =
      ImmutableList.of(
          "rules_cc/cc/private/toolchain/unix_cc_configure.bzl",
          "bazel_tools/tools/cpp/unix_cc_configure.bzl");

  /** Max. number of command line args added as a profiler description. */
  private static final int MAX_PROFILE_ARGS_LEN = 80;

  private final Rule rule;
  private final PathPackageLocator packageLocator;
  private final Path outputDirectory;
  private final Path embeddedBinariesRoot;
  private final StructImpl attrObject;
  private final SkylarkOS osObject;
  private final ImmutableSet<PathFragment> blacklistedPatterns;
  private final Environment env;
  private final DownloadManager downloadManager;
  private final double timeoutScaling;
  private final Map<String, String> markerData;
  private final StarlarkSemantics starlarkSemantics;
  private final RepositoryRemoteExecutor remoteExecutor;

  /**
   * Create a new context (repository_ctx) object for a Starlark repository rule ({@code rule}
   * argument).
   */
  SkylarkRepositoryContext(
      Rule rule,
      PathPackageLocator packageLocator,
      Path outputDirectory,
      ImmutableSet<PathFragment> blacklistedPatterns,
      Environment environment,
      Map<String, String> env,
      DownloadManager downloadManager,
      Path embeddedBinariesRoot,
      double timeoutScaling,
      Map<String, String> markerData,
      StarlarkSemantics starlarkSemantics,
      @Nullable RepositoryRemoteExecutor remoteExecutor)
      throws EvalException {
    this.rule = rule;
    this.packageLocator = packageLocator;
    this.outputDirectory = outputDirectory;
    this.embeddedBinariesRoot = embeddedBinariesRoot;
    this.blacklistedPatterns = blacklistedPatterns;
    this.env = environment;
    this.osObject = new SkylarkOS(env);
    this.downloadManager = downloadManager;
    this.timeoutScaling = timeoutScaling;
    this.markerData = markerData;
    WorkspaceAttributeMapper attrs = WorkspaceAttributeMapper.of(rule);
    ImmutableMap.Builder<String, Object> attrBuilder = new ImmutableMap.Builder<>();
    for (String name : attrs.getAttributeNames()) {
      if (!name.equals("$local")) {
        // Attribute values should be type safe
        attrBuilder.put(
            Attribute.getSkylarkName(name), Starlark.fromJava(attrs.getObject(name), null));
      }
    }
    attrObject = StructProvider.STRUCT.create(attrBuilder.build(), "No such attribute '%s'");
    this.starlarkSemantics = starlarkSemantics;
    this.remoteExecutor = remoteExecutor;
  }

  @Override
  public String getName() {
    return rule.getName();
  }

  @Override
  public StructImpl getAttr() {
    return attrObject;
  }

  private SkylarkPath externalPath(String method, Object pathObject)
      throws EvalException, InterruptedException {
    SkylarkPath skylarkPath = getPath(method, pathObject);
    Path path = skylarkPath.getPath();
    if (packageLocator.getPathEntries().stream().noneMatch(root -> path.startsWith(root.asPath()))
        || path.startsWith(outputDirectory)) {
      return skylarkPath;
    }
    Path workspaceRoot = packageLocator.getWorkspaceFile().getParentDirectory();
    PathFragment relativePath = path.relativeTo(workspaceRoot);
    for (PathFragment blacklistedPattern : blacklistedPatterns) {
      if (relativePath.startsWith(blacklistedPattern)) {
        return skylarkPath;
      }
    }
    throw Starlark.errorf(
        "%s can only be applied to external paths (that is, outside the workspace or ignored in"
            + " .bazelignore)",
        method);
  }

  @Override
  public SkylarkPath path(Object path) throws EvalException, InterruptedException {
    return getPath("path()", path);
  }

  private SkylarkPath getPath(String method, Object path)
      throws EvalException, InterruptedException {
    if (path instanceof String) {
      PathFragment pathFragment = PathFragment.create(path.toString());
      return new SkylarkPath(
          pathFragment.isAbsolute()
              ? outputDirectory.getFileSystem().getPath(path.toString())
              : outputDirectory.getRelative(pathFragment));
    } else if (path instanceof Label) {
      return getPathFromLabel((Label) path);
    } else if (path instanceof SkylarkPath) {
      return (SkylarkPath) path;
    } else {
      throw Starlark.errorf("%s can only take a string or a label.", method);
    }
  }

  @Override
  public void reportProgress(String status) {
    final String message = status == null ? "" : status;
    final String id = "@" + getName();

    env.getListener()
        .post(
            new FetchProgress() {
              @Override
              public String getResourceIdentifier() {
                return id;
              }

              @Override
              public String getProgress() {
                return message;
              }

              @Override
              public boolean isFinished() {
                return false;
              }
            });
  }

  @Override
  public void symlink(Object from, Object to, StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    SkylarkPath fromPath = getPath("symlink()", from);
    SkylarkPath toPath = getPath("symlink()", to);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newSymlinkEvent(
            fromPath.toString(),
            toPath.toString(),
            rule.getLabel().toString(),
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      checkInOutputDirectory("write", toPath);
      makeDirectories(toPath.getPath());
      toPath.getPath().createSymbolicLink(fromPath.getPath());
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Could not create symlink from " + fromPath + " to " + toPath + ": " + e.getMessage(),
              e),
          Transience.TRANSIENT);
    }
  }

  private void checkInOutputDirectory(String operation, SkylarkPath path)
      throws RepositoryFunctionException {
    if (!path.getPath().getPathString().startsWith(outputDirectory.getPathString())) {
      throw new RepositoryFunctionException(
          Starlark.errorf(
              "Cannot %s outside of the repository directory for path %s", operation, path),
          Transience.PERSISTENT);
    }
  }

  @Override
  public void createFile(
      Object path, String content, Boolean executable, Boolean legacyUtf8, StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    SkylarkPath p = getPath("file()", path);
    byte[] contentBytes;
    if (legacyUtf8) {
      contentBytes = content.getBytes(StandardCharsets.UTF_8);
    } else {
      contentBytes = content.getBytes(StandardCharsets.ISO_8859_1);
    }
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newFileEvent(
            p.toString(),
            content,
            executable,
            rule.getLabel().toString(),
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      checkInOutputDirectory("write", p);
      makeDirectories(p.getPath());
      p.getPath().delete();
      try (OutputStream stream = p.getPath().getOutputStream()) {
        stream.write(contentBytes);
      }
      if (executable) {
        p.getPath().setExecutable(true);
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @Override
  public void createFileFromTemplate(
      Object path,
      Object template,
      Dict<?, ?> substitutions, // <String, String> expected
      Boolean executable,
      StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    SkylarkPath p = getPath("template()", path);
    SkylarkPath t = getPath("template()", template);
    Map<String, String> substitutionMap =
        Dict.cast(substitutions, String.class, String.class, "substitutions");
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newTemplateEvent(
            p.toString(),
            t.toString(),
            substitutionMap,
            executable,
            rule.getLabel().toString(),
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      checkInOutputDirectory("write", p);
      makeDirectories(p.getPath());
      String tpl = FileSystemUtils.readContent(t.getPath(), StandardCharsets.UTF_8);
      for (Map.Entry<String, String> substitution : substitutionMap.entrySet()) {
        tpl =
            StringUtilities.replaceAllLiteral(tpl, substitution.getKey(), substitution.getValue());
      }
      p.getPath().delete();
      try (OutputStream stream = p.getPath().getOutputStream()) {
        stream.write(tpl.getBytes(StandardCharsets.UTF_8));
      }
      if (executable) {
        p.getPath().setExecutable(true);
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @Override
  public String readFile(Object path, StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    SkylarkPath p = getPath("read()", path);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newReadEvent(
            p.toString(), rule.getLabel().toString(), thread.getCallerLocation());
    env.getListener().post(w);
    try {
      return FileSystemUtils.readContent(p.getPath(), StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  // Create parent directories for the given path
  private void makeDirectories(Path path) throws IOException {
    Path parent = path.getParentDirectory();
    if (parent != null) {
      parent.createDirectoryAndParents();
    }
  }

  @Override
  public SkylarkOS getOS() {
    // Historically this event reported the location of the ctx.os expression,
    // but that's no longer available in the interpreter API. Now we report the
    // location of the rule's implementation function, and the user must inspect
    // that code manually (or in a debugger) to find the offending ctx.os expression.
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newOsEvent(
            rule.getLabel().toString(),
            rule.getRuleClassObject().getConfiguredTargetFunction().getLocation());
    env.getListener().post(w);
    return osObject;
  }

  private void createDirectory(Path directory) throws RepositoryFunctionException {
    try {
      if (!directory.exists()) {
        makeDirectories(directory);
        directory.createDirectory();
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  boolean isRemotable() {
    Object remotable = rule.getAttributeContainer().getAttr("$remotable");
    if (remotable != null) {
      return (Boolean) remotable;
    }
    return false;
  }

  private boolean canExecuteRemote() {
    boolean featureEnabled = starlarkSemantics.experimentalRepoRemoteExec();
    boolean remoteExecEnabled = remoteExecutor != null;
    return featureEnabled && isRemotable() && remoteExecEnabled;
  }

  private ImmutableMap<String, String> getExecProperties() throws EvalException {
    return ImmutableMap.copyOf(
        Dict.cast(
            getAttr().getValue("exec_properties"), String.class, String.class, "exec_properties"));
  }

  private Map.Entry<PathFragment, Path> getRemotePathFromLabel(Label label)
      throws EvalException, InterruptedException {
    Path localPath = getPathFromLabel(label).getPath();
    PathFragment remotePath =
        label.getPackageIdentifier().getSourceRoot().getRelative(label.getName());
    return Maps.immutableEntry(remotePath, localPath);
  }

  private SkylarkExecutionResult executeRemote(
      Sequence<?> argumentsUnchecked, // <String> or <Label> expected
      int timeout,
      Map<String, String> environment,
      boolean quiet,
      String workingDirectory)
      throws EvalException, InterruptedException {
    Preconditions.checkState(canExecuteRemote());

    ImmutableSortedMap.Builder<PathFragment, Path> inputsBuilder =
        ImmutableSortedMap.naturalOrder();
    ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
    for (Object argumentUnchecked : argumentsUnchecked) {
      if (argumentUnchecked instanceof Label) {
        Label label = (Label) argumentUnchecked;
        Map.Entry<PathFragment, Path> remotePath = getRemotePathFromLabel(label);
        argumentsBuilder.add(remotePath.getKey().toString());
        inputsBuilder.put(remotePath);
      } else {
        argumentsBuilder.add(argumentUnchecked.toString());
      }
    }

    ImmutableList<String> arguments = argumentsBuilder.build();

    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.STARLARK_REPOSITORY_FN, profileArgsDesc("remote", arguments))) {
      ExecutionResult result =
          remoteExecutor.execute(
              arguments,
              inputsBuilder.build(),
              getExecProperties(),
              ImmutableMap.copyOf(environment),
              workingDirectory,
              Duration.ofSeconds(timeout));

      String stdout = new String(result.stdout(), StandardCharsets.US_ASCII);
      String stderr = new String(result.stderr(), StandardCharsets.US_ASCII);

      if (!quiet) {
        OutErr outErr = OutErr.SYSTEM_OUT_ERR;
        outErr.printOut(stdout);
        outErr.printErr(stderr);
      }

      return new SkylarkExecutionResult(result.exitCode(), stdout, stderr);
    } catch (IOException e) {
      throw Starlark.errorf("remote_execute failed: %s", e.getMessage());
    }
  }

  private void validateExecuteArguments(Sequence<?> arguments) throws EvalException {
    boolean isRemotable = isRemotable();
    for (int i = 0; i < arguments.size(); i++) {
      Object arg = arguments.get(i);
      if (isRemotable) {
        if (!(arg instanceof String || arg instanceof Label)) {
          throw new EvalException(
              Location.BUILTIN, "Argument " + i + " of execute is neither a label nor a string.");
        }
      } else {
        if (!(arg instanceof String || arg instanceof SkylarkPath)) {
          throw new EvalException(
              Location.BUILTIN, "Argument " + i + " of execute is neither a path nor a string.");
        }
      }
    }
  }

  /** Returns the command line arguments as a string for display in the profiler. */
  private static String profileArgsDesc(String method, List<String> args) {
    StringBuilder b = new StringBuilder();
    b.append(method).append(":");

    final String sep = " ";
    for (String arg : args) {
      int appendLen = sep.length() + arg.length();
      int remainingLen = MAX_PROFILE_ARGS_LEN - b.length();

      if (appendLen <= remainingLen) {
        b.append(sep);
        b.append(arg);
      } else {
        String shortenedArg = (sep + arg).substring(0, remainingLen);
        b.append(shortenedArg);
        b.append("...");
        break;
      }
    }

    return b.toString();
  }

  @Override
  public SkylarkExecutionResult execute(
      Sequence<?> arguments, // <String> or <SkylarkPath> or <Label> expected
      Integer timeout,
      Dict<?, ?> uncheckedEnvironment, // <String, String> expected
      boolean quiet,
      String workingDirectory,
      StarlarkThread thread)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    validateExecuteArguments(arguments);

    Map<String, String> environment =
        Dict.cast(uncheckedEnvironment, String.class, String.class, "environment");

    if (canExecuteRemote()) {
      return executeRemote(arguments, timeout, environment, quiet, workingDirectory);
    }

    // Execute on the local/host machine

    List<String> args = new ArrayList<>(arguments.size());
    for (Object arg : arguments) {
      if (arg instanceof Label) {
        args.add(getPathFromLabel((Label) arg).toString());
      } else {
        // String or SkylarkPath expected
        args.add(arg.toString());
      }
    }

    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newExecuteEvent(
            args,
            timeout,
            osObject.getEnvironmentVariables(),
            environment,
            outputDirectory.getPathString(),
            quiet,
            rule.getLabel().toString(),
            thread.getCallerLocation());
    env.getListener().post(w);
    createDirectory(outputDirectory);

    long timeoutMillis = Math.round(timeout.longValue() * 1000 * timeoutScaling);
    if (OS.getCurrent() != OS.WINDOWS && embeddedBinariesRoot != null) {
      Path processWrapper = ProcessWrapperUtil.getProcessWrapper(embeddedBinariesRoot);
      if (processWrapper.exists()) {
        args =
            ProcessWrapperUtil.commandLineBuilder(processWrapper.getPathString(), args)
                .setTimeout(Duration.ofMillis(timeoutMillis))
                .build();
      }
    }

    Path workingDirectoryPath = outputDirectory;
    if (workingDirectory != null && !workingDirectory.isEmpty()) {
      workingDirectoryPath = getPath("execute()", workingDirectory).getPath();
    }
    createDirectory(workingDirectoryPath);

    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.STARLARK_REPOSITORY_FN, profileArgsDesc("local", args))) {
      return SkylarkExecutionResult.builder(osObject.getEnvironmentVariables())
          .addArguments(args)
          .setDirectory(workingDirectoryPath.getPathFile())
          .addEnvironmentVariables(environment)
          .setTimeout(timeoutMillis)
          .setQuiet(quiet)
          .execute();
    }
  }

  @Override
  public boolean delete(Object pathObject, StarlarkThread thread)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    SkylarkPath skylarkPath = externalPath("delete()", pathObject);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newDeleteEvent(
            skylarkPath.toString(), rule.getLabel().toString(), thread.getCallerLocation());
    env.getListener().post(w);
    try {
      Path path = skylarkPath.getPath();
      FileSystem fileSystem = path.getFileSystem();
      fileSystem.deleteTreesBelow(path);
      return fileSystem.delete(path);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @Override
  public void patch(Object patchFile, Integer strip, StarlarkThread thread)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    SkylarkPath skylarkPath = getPath("patch()", patchFile);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newPatchEvent(
            skylarkPath.toString(), strip, rule.getLabel().toString(), thread.getCallerLocation());
    env.getListener().post(w);
    try {
      PatchUtil.apply(skylarkPath.getPath(), strip, outputDirectory);
    } catch (PatchFailedException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Error applying patch %s: %s", skylarkPath, e.getMessage()),
          Transience.TRANSIENT);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @Override
  public SkylarkPath which(String program, StarlarkThread thread) throws EvalException {
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newWhichEvent(
            program, rule.getLabel().toString(), thread.getCallerLocation());
    env.getListener().post(w);
    if (program.contains("/") || program.contains("\\")) {
      throw Starlark.errorf(
          "Program argument of which() may not contains a / or a \\ ('%s' given)", program);
    }
    try {
      SkylarkPath commandPath = findCommandOnPath(program);
      if (commandPath != null) {
        return commandPath;
      }

      if (!program.endsWith(OsUtils.executableExtension())) {
        program += OsUtils.executableExtension();
        return findCommandOnPath(program);
      }
    } catch (IOException e) {
      // IOException when checking executable file means we cannot read the file data so
      // we cannot execute it, swallow the exception.
    }
    return null;
  }

  private SkylarkPath findCommandOnPath(String program) throws IOException {
    for (String p : getPathEnvironment()) {
      PathFragment fragment = PathFragment.create(p);
      if (fragment.isAbsolute()) {
        // We ignore relative path as they don't mean much here (relative to where? the workspace
        // root?).
        Path path = outputDirectory.getFileSystem().getPath(fragment).getChild(program);
        if (path.exists() && path.isFile(Symlinks.FOLLOW) && path.isExecutable()) {
          return new SkylarkPath(path);
        }
      }
    }
    return null;
  }

  private void warnAboutChecksumError(List<URL> urls, String errorMessage) {
    // Inform the user immediately, even though the file will still be downloaded.
    // This cannot be done by a regular error event, as all regular events are recorded
    // and only shown once the execution of the repository rule is finished.
    // So we have to provide the information as update on the progress
    String url = "(unknown)";
    if (urls.size() > 0) {
      url = urls.get(0).toString();
    }
    reportProgress("Will fail after download of " + url + ". " + errorMessage);
  }

  private static Map<String, Dict<?, ?>> getAuthContents(Dict<?, ?> x, String what)
      throws EvalException {
    // Dict.cast returns Dict<String, raw Dict>.
    @SuppressWarnings({"unchecked", "rawtypes"})
    Map<String, Dict<?, ?>> res = (Map) Dict.cast(x, String.class, Dict.class, what);
    return res;
  }

  @Override
  public StructImpl download(
      Object url,
      Object output,
      String sha256,
      Boolean executable,
      Boolean allowFail,
      String canonicalId,
      Dict<?, ?> authUnchecked, // <String, Dict> expected
      String integrity,
      StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    Map<URI, Map<String, String>> authHeaders =
        getAuthHeaders(getAuthContents(authUnchecked, "auth"));

    List<URL> urls =
        getUrls(
            url,
            /* ensureNonEmpty= */ !allowFail,
            /* checksumGiven= */ !Strings.isNullOrEmpty(sha256)
                || !Strings.isNullOrEmpty(integrity));
    Optional<Checksum> checksum;
    RepositoryFunctionException checksumValidation = null;
    try {
      checksum = validateChecksum(sha256, integrity, urls);
    } catch (RepositoryFunctionException e) {
      checksum = Optional.<Checksum>absent();
      checksumValidation = e;
    }

    SkylarkPath outputPath = getPath("download()", output);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newDownloadEvent(
            urls,
            output.toString(),
            sha256,
            integrity,
            executable,
            rule.getLabel().toString(),
            thread.getCallerLocation());
    env.getListener().post(w);
    Path downloadedPath;
    try (SilentCloseable c =
        Profiler.instance().profile("fetching: " + rule.getLabel().toString())) {
      checkInOutputDirectory("write", outputPath);
      makeDirectories(outputPath.getPath());
      downloadedPath =
          downloadManager.download(
              urls,
              authHeaders,
              checksum,
              canonicalId,
              Optional.<String>absent(),
              outputPath.getPath(),
              env.getListener(),
              osObject.getEnvironmentVariables(),
              getName());
      if (executable) {
        outputPath.getPath().setExecutable(true);
      }
    } catch (InterruptedException e) {
      throw new RepositoryFunctionException(
          new IOException("thread interrupted"), Transience.TRANSIENT);
    } catch (IOException e) {
      if (allowFail) {
        Dict<String, Object> dict = Dict.of((Mutability) null, "success", false);
        return StructProvider.STRUCT.createWithBuiltinLocation(dict);
      } else {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    }
    if (checksumValidation != null) {
      throw checksumValidation;
    }

    return calculateDownloadResult(checksum, downloadedPath);
  }

  @Override
  public void extract(Object archive, Object output, String stripPrefix, StarlarkThread thread)
      throws RepositoryFunctionException, InterruptedException, EvalException {
    SkylarkPath archivePath = getPath("extract()", archive);

    if (!archivePath.exists()) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Archive path '%s' does not exist.", archivePath), Transience.TRANSIENT);
    }

    SkylarkPath outputPath = getPath("extract()", output);
    checkInOutputDirectory("write", outputPath);

    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newExtractEvent(
            archive.toString(),
            output.toString(),
            stripPrefix,
            rule.getLabel().toString(),
            thread.getCallerLocation());
    env.getListener().post(w);

    env.getListener()
        .post(
            new ExtractProgress(
                outputPath.getPath().toString(), "Extracting " + archivePath.getPath()));
    DecompressorValue.decompress(
        DecompressorDescriptor.builder()
            .setTargetKind(rule.getTargetKind())
            .setTargetName(rule.getName())
            .setArchivePath(archivePath.getPath())
            .setRepositoryPath(outputPath.getPath())
            .setPrefix(stripPrefix)
            .build());
    env.getListener().post(new ExtractProgress(outputPath.getPath().toString()));
  }

  @Override
  public StructImpl downloadAndExtract(
      Object url,
      Object output,
      String sha256,
      String type,
      String stripPrefix,
      Boolean allowFail,
      String canonicalId,
      Dict<?, ?> auth, // <String, Dict> expected
      String integrity,
      StarlarkThread thread)
      throws RepositoryFunctionException, InterruptedException, EvalException {
    Map<URI, Map<String, String>> authHeaders = getAuthHeaders(getAuthContents(auth, "auth"));

    List<URL> urls =
        getUrls(
            url,
            /* ensureNonEmpty= */ !allowFail,
            /* checksumGiven= */ !Strings.isNullOrEmpty(sha256)
                || !Strings.isNullOrEmpty(integrity));
    Optional<Checksum> checksum;
    RepositoryFunctionException checksumValidation = null;
    try {
      checksum = validateChecksum(sha256, integrity, urls);
    } catch (RepositoryFunctionException e) {
      checksum = Optional.<Checksum>absent();
      checksumValidation = e;
    }

    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newDownloadAndExtractEvent(
            urls,
            output.toString(),
            sha256,
            integrity,
            type,
            stripPrefix,
            rule.getLabel().toString(),
            thread.getCallerLocation());

    // Download to outputDirectory and delete it after extraction
    SkylarkPath outputPath = getPath("download_and_extract()", output);
    checkInOutputDirectory("write", outputPath);
    createDirectory(outputPath.getPath());

    Path downloadedPath;
    try (SilentCloseable c =
        Profiler.instance().profile("fetching: " + rule.getLabel().toString())) {
      downloadedPath =
          downloadManager.download(
              urls,
              authHeaders,
              checksum,
              canonicalId,
              Optional.of(type),
              outputPath.getPath(),
              env.getListener(),
              osObject.getEnvironmentVariables(),
              getName());
    } catch (InterruptedException e) {
      env.getListener().post(w);
      throw new RepositoryFunctionException(
          new IOException("thread interrupted"), Transience.TRANSIENT);
    } catch (IOException e) {
      env.getListener().post(w);
      if (allowFail) {
        Dict<String, Object> dict = Dict.of((Mutability) null, "success", false);
        return StructProvider.STRUCT.createWithBuiltinLocation(dict);
      } else {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    }
    if (checksumValidation != null) {
      throw checksumValidation;
    }
    env.getListener().post(w);
    try (SilentCloseable c =
        Profiler.instance().profile("extracting: " + rule.getLabel().toString())) {
      env.getListener()
          .post(
              new ExtractProgress(outputPath.getPath().toString(), "Extracting " + downloadedPath));
      DecompressorValue.decompress(
          DecompressorDescriptor.builder()
              .setTargetKind(rule.getTargetKind())
              .setTargetName(rule.getName())
              .setArchivePath(downloadedPath)
              .setRepositoryPath(outputPath.getPath())
              .setPrefix(stripPrefix)
              .build());
      env.getListener().post(new ExtractProgress(outputPath.getPath().toString()));
    }

    StructImpl downloadResult = calculateDownloadResult(checksum, downloadedPath);
    try {
      if (downloadedPath.exists()) {
        downloadedPath.delete();
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Couldn't delete temporary file (" + downloadedPath.getPathString() + ")", e),
          Transience.TRANSIENT);
    }
    return downloadResult;
  }

  @Override
  public boolean flagEnabled(String flag, StarlarkThread starlarkThread) throws EvalException {
    try {
      if (WHITELISTED_PATHS_FOR_FLAG_ENABLED.stream()
          .noneMatch(x -> !starlarkThread.getCallerLocation().toString().endsWith(x))) {
        throw Starlark.errorf(
            "flag_enabled() is restricted to: '%s'.",
            Joiner.on(", ").join(WHITELISTED_REPOS_FOR_FLAG_ENABLED));
      }
      return starlarkSemantics.flagValue(flag);
    } catch (IllegalArgumentException e) {
      throw Starlark.errorf("Can't query value of '%s'.\n%s", flag, e.getMessage());
    }
  }

  private Checksum calculateChecksum(Optional<Checksum> originalChecksum, Path path)
      throws IOException, InterruptedException {
    if (originalChecksum.isPresent()) {
      // The checksum is checked on download, so if we got here, the user provided checksum is good
      return originalChecksum.get();
    }
    return Checksum.fromString(KeyType.SHA256, RepositoryCache.getChecksum(KeyType.SHA256, path));
  }

  private Optional<Checksum> validateChecksum(String sha256, String integrity, List<URL> urls)
      throws RepositoryFunctionException, EvalException {
    if (!sha256.isEmpty()) {
      if (!integrity.isEmpty()) {
        throw Starlark.errorf("Expected either 'sha256' or 'integrity', but not both");
      }
      try {
        return Optional.of(Checksum.fromString(KeyType.SHA256, sha256));
      } catch (IllegalArgumentException e) {
        warnAboutChecksumError(urls, e.getMessage());
        throw new RepositoryFunctionException(
            Starlark.errorf(
                "Definition of repository %s: %s at %s",
                rule.getName(), e.getMessage(), rule.getLocation()),
            Transience.PERSISTENT);
      }
    }

    if (integrity.isEmpty()) {
      return Optional.absent();
    }

    try {
      return Optional.of(Checksum.fromSubresourceIntegrity(integrity));
    } catch (IllegalArgumentException e) {
      warnAboutChecksumError(urls, e.getMessage());
      throw new RepositoryFunctionException(
          Starlark.errorf(
              "Definition of repository %s: %s at %s",
              rule.getName(), e.getMessage(), rule.getLocation()),
          Transience.PERSISTENT);
    }
  }

  private StructImpl calculateDownloadResult(Optional<Checksum> checksum, Path downloadedPath)
      throws EvalException, InterruptedException, RepositoryFunctionException {
    Checksum finalChecksum;
    try {
      finalChecksum = calculateChecksum(checksum, downloadedPath);
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Couldn't hash downloaded file (" + downloadedPath.getPathString() + ")", e),
          Transience.PERSISTENT);
    }

    ImmutableMap.Builder<String, Object> out = new ImmutableMap.Builder<>();
    out.put("success", true);
    out.put("integrity", finalChecksum.toSubresourceIntegrity());

    // For compatibility with older Bazel versions that don't support non-SHA256 checksums.
    if (finalChecksum.getKeyType() == KeyType.SHA256) {
      out.put("sha256", finalChecksum.toString());
    }
    return StructProvider.STRUCT.createWithBuiltinLocation(Dict.copyOf(null, out.build()));
  }

  private static ImmutableList<String> checkAllUrls(Iterable<?> urlList) throws EvalException {
    ImmutableList.Builder<String> result = ImmutableList.builder();

    for (Object o : urlList) {
      if (!(o instanceof String)) {
        throw new EvalException(
            null,
            String.format(
                "Expected a string or sequence of strings for 'url' argument, "
                    + "but got '%s' item in the sequence",
                Starlark.type(o)));
      }
      result.add((String) o);
    }

    return result.build();
  }

  private static List<URL> getUrls(Object urlOrList, boolean ensureNonEmpty, boolean checksumGiven)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    List<String> urlStrings;
    if (urlOrList instanceof String) {
      urlStrings = ImmutableList.of((String) urlOrList);
    } else {
      urlStrings = checkAllUrls((Iterable<?>) urlOrList);
    }
    if (ensureNonEmpty && urlStrings.isEmpty()) {
      throw new RepositoryFunctionException(new IOException("urls not set"), Transience.PERSISTENT);
    }
    List<URL> urls = new ArrayList<>();
    for (String urlString : urlStrings) {
      URL url;
      try {
        url = new URL(urlString);
      } catch (MalformedURLException e) {
        throw new RepositoryFunctionException(
            new IOException("Bad URL: " + urlString), Transience.PERSISTENT);
      }
      if (!HttpUtils.isUrlSupportedByDownloader(url)) {
        throw new RepositoryFunctionException(
            new IOException("Unsupported protocol: " + url.getProtocol()), Transience.PERSISTENT);
      }
      if (!checksumGiven) {
        if (!Ascii.equalsIgnoreCase("http", url.getProtocol())) {
          urls.add(url);
        }
      } else {
        urls.add(url);
      }
    }
    if (ensureNonEmpty && urls.isEmpty()) {
      throw new RepositoryFunctionException(
          new IOException(
              "No URLs left after removing plain http URLs due to missing checksum."
                  + " Please provide either a checksum or an https download location."),
          Transience.PERSISTENT);
    }
    return urls;
  }

  // This is just for test to overwrite the path environment
  private static ImmutableList<String> pathEnv = null;

  @VisibleForTesting
  static void setPathEnvironment(String... pathEnv) {
    SkylarkRepositoryContext.pathEnv = ImmutableList.<String>copyOf(pathEnv);
  }

  private ImmutableList<String> getPathEnvironment() {
    if (pathEnv != null) {
      return pathEnv;
    }
    String pathEnviron = osObject.getEnvironmentVariables().get("PATH");
    if (pathEnviron == null) {
      return ImmutableList.of();
    }
    return ImmutableList.copyOf(pathEnviron.split(File.pathSeparator));
  }

  @Override
  public String toString() {
    return "repository_ctx[" + rule.getLabel() + "]";
  }

  // Resolve the label given by value into a file path.
  private SkylarkPath getPathFromLabel(Label label) throws EvalException, InterruptedException {
    RootedPath rootedPath = RepositoryFunction.getRootedPathFromLabel(label, env);
    SkyKey fileSkyKey = FileValue.key(rootedPath);
    FileValue fileValue = null;
    try {
      fileValue = (FileValue) env.getValueOrThrow(fileSkyKey, IOException.class);
    } catch (IOException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }

    if (fileValue == null) {
      throw RepositoryFunction.restart();
    }
    if (!fileValue.isFile() || fileValue.isSpecialFile()) {
      throw Starlark.errorf("Not a regular file: %s", rootedPath.asPath().getPathString());
    }

    // A label does not contains space so it safe to use as a key.
    try {
      markerData.put("FILE:" + label, RepositoryFunction.fileValueToMarkerValue(fileValue));
    } catch (IOException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
    return new SkylarkPath(rootedPath.asPath());
  }

  /**
   * Try to compute the paths of all attributes that are labels, including labels in list and dict
   * arguments.
   *
   * <p>The value is ignored, but any missing information from the environment is detected (and an
   * exception thrown). In this way, we can enforce that all arguments are evaluated before we start
   * potentially more expensive operations.
   */
  public void enforceLabelAttributes() throws EvalException, InterruptedException {
    StructImpl attr = getAttr();
    for (String name : attr.getFieldNames()) {
      Object value = attr.getValue(name);
      if (value instanceof Label) {
        getPathFromLabel((Label) value);
      }
      if (value instanceof Sequence) {
        for (Object entry : (Sequence) value) {
          if (entry instanceof Label) {
            getPathFromLabel((Label) entry);
          }
        }
      }
      if (value instanceof Dict) {
        for (Object entry : ((Dict) value).keySet()) {
          if (entry instanceof Label) {
            getPathFromLabel((Label) entry);
          }
        }
      }
    }
  }

  /**
   * From an authentication dict extract a map of headers.
   *
   * <p>Given a dict as provided as "auth" argument, compute a map specifying for each URI provided
   * which additional headers (as usual, represented as a map from Strings to Strings) should
   * additionally be added to the request. For some form of authentication, in particular basic
   * authentication, adding those headers is enough; for other forms of authentication other
   * measures might be necessary.
   */
  private static Map<URI, Map<String, String>> getAuthHeaders(Map<String, Dict<?, ?>> auth)
      throws RepositoryFunctionException, EvalException {
    ImmutableMap.Builder<URI, Map<String, String>> headers = new ImmutableMap.Builder<>();
    for (Map.Entry<String, Dict<?, ?>> entry : auth.entrySet()) {
      try {
        URL url = new URL(entry.getKey());
        Dict<?, ?> authMap = entry.getValue();
        if (authMap.containsKey("type")) {
          if ("basic".equals(authMap.get("type"))) {
            if (!authMap.containsKey("login") || !authMap.containsKey("password")) {
              throw new EvalException(
                  null,
                  "Found request to do basic auth for "
                      + entry.getKey()
                      + " without 'login' and 'password' being provided.");
            }
            String credentials = authMap.get("login") + ":" + authMap.get("password");
            headers.put(
                url.toURI(),
                ImmutableMap.<String, String>of(
                    "Authorization",
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8))));
          } else if ("pattern".equals(authMap.get("type"))) {
            if (!authMap.containsKey("pattern")) {
              throw new EvalException(
                  null,
                  "Found request to do pattern auth for "
                      + entry.getKey()
                      + " without a pattern being provided");
            }

            String result = (String) authMap.get("pattern");

            for (String component : Arrays.asList("password", "login")) {
              String demarcatedComponent = "<" + component + ">";

              if (result.contains(demarcatedComponent)) {
                if (!authMap.containsKey(component)) {
                  throw new EvalException(
                      null,
                      "Auth pattern contains "
                          + demarcatedComponent
                          + " but it was not provided in auth dict.");
                }
              } else {
                // component isn't in the pattern, ignore it
                continue;
              }

              result = result.replaceAll(demarcatedComponent, (String) authMap.get(component));
            }

            headers.put(url.toURI(), ImmutableMap.<String, String>of("Authorization", result));
          }
        }
      } catch (MalformedURLException e) {
        throw new RepositoryFunctionException(e, Transience.PERSISTENT);
      } catch (URISyntaxException e) {
        throw new EvalException(null, e.getMessage());
      }
    }
    return headers.build();
  }

  private static class ExtractProgress implements FetchProgress {
    private final String repositoryPath;
    private final String progress;
    private final boolean isFinished;

    ExtractProgress(String repositoryPath, String progress) {
      this.repositoryPath = repositoryPath;
      this.progress = progress;
      this.isFinished = false;
    }

    ExtractProgress(String repositoryPath) {
      this.repositoryPath = repositoryPath;
      this.progress = "";
      this.isFinished = true;
    }

    @Override
    public String getResourceIdentifier() {
      return repositoryPath;
    }

    @Override
    public String getProgress() {
      return progress;
    }

    @Override
    public boolean isFinished() {
      return isFinished;
    }
  }
}
