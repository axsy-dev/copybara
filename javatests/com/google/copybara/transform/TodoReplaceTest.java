/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.transform;

import static com.google.common.truth.Truth.assertThat;
import static com.google.copybara.testing.FileSubjects.assertThatPath;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.jimfs.Jimfs;
import com.google.copybara.Core;
import com.google.copybara.NonReversibleValidationException;
import com.google.copybara.TransformWork;
import com.google.copybara.Transformation;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.testing.TransformWorks;
import com.google.copybara.util.console.testing.TestingConsole;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TodoReplaceTest {

  private Path checkoutDir;
  private TestingConsole console;
  private SkylarkTestExecutor skylark;

  @Before
  public void setup() throws IOException {
    FileSystem fs = Jimfs.newFileSystem();
    checkoutDir = fs.getPath("/");
    Files.createDirectories(checkoutDir);
    console = new TestingConsole();
    OptionsBuilder options = new OptionsBuilder()
        .setConsole(console);
    skylark = new SkylarkTestExecutor(options, Core.class);
  }


  @Test
  public void testErrors() throws ValidationException {
    skylark.evalFails("core.todo_replace(tags = [])", "'tags' cannot be empty");
    skylark.evalFails("core.todo_replace(mode = 'USE_DEFAULT')",
        "'default' needs to be set for mode 'USE_DEFAULT'");
    skylark.evalFails("core.todo_replace(mode = 'MAP_OR_DEFAULT')",
        "'default' needs to be set for mode 'MAP_OR_DEFAULT'");
    skylark.evalFails("core.todo_replace(mode = 'MAP_OR_IGNORE', default = 'aaa')",
        "'default' cannot be used for mode 'MAP_OR_IGNORE'");
    skylark.evalFails(
        "core.todo_replace(mode = 'USE_DEFAULT', default = 'aaa', mapping = {'a':'b'})",
        "'mapping' cannot be used with mode USE_DEFAULT");
  }

  @Test
  public void testReversibleErrors() throws Exception {
    TodoReplace replace = todoReplace("mode = 'USE_DEFAULT', default = 'a'");
    try{
      replace.reverse();
      fail();
    } catch (NonReversibleValidationException expected) {
      // Exoected
    }

  }

  @Test
  public void testMappingNotReversable() throws Exception {
    // Reverse works if map is bidirectional
    todoReplace("mapping = { 'aaa': 'foo', 'bbb' : 'bar'}").reverse().reverse();

    // But fails if not:
    try{
      todoReplace("mapping = { 'aaa': 'bar', 'bbb' : 'bar'}").reverse();
      fail();
    } catch (NonReversibleValidationException expected) {
      // Exoected
    }
  }

  @Test
  public void testMapping() throws Exception {
    TodoReplace replace = todoReplace("mapping = { 'aaa': 'foo', 'bbb' : 'bar'}");
    write("one", ""
        + "aaa\n"
        + "// TODO( aaa, bbb,other): Example\n");
    write("two", "// TODO(aaa): Other Example\n");
    TransformWork work = run(replace);

    assertThatPath(checkoutDir)
        .containsFile("one", ""
            + "aaa\n"
            + "// TODO( foo, bar,other): Example\n")
        .containsFile("two", "// TODO(foo): Other Example\n")
        .containsNoMoreFiles();

    run(replace.reverse());

    assertThatPath(checkoutDir)
        .containsFile("one", ""
            + "aaa\n"
            + "// TODO( aaa, bbb,other): Example\n")
        .containsFile("two", "// TODO(aaa): Other Example\n")
        .containsNoMoreFiles();
  }

  @Test
  public void testMultipleNotFound() throws Exception {
    TodoReplace replace = todoReplace("mapping = { 'test': 'foo'}");
    write("one", "# TODO(danmane,mrry,opensource): Flip these switches\n");
    run(replace);

    assertThatPath(checkoutDir)
        .containsFile("one", "# TODO(danmane,mrry,opensource): Flip these switches\n")
        .containsNoMoreFiles();

  }

  @Test
  public void testMapOrFail() throws Exception {
    TodoReplace replace = todoReplace(
        "mapping = { 'aaa': 'foo'}",
        "mode = 'MAP_OR_FAIL'");

    write("one.txt", "// TODO(aaa): Example\n");
    run(replace);

    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO(foo): Example\n")
        .containsNoMoreFiles();

    write("one.txt", "// TODO(bbb): Example\n");

    try {
      run(replace);
      fail();
    } catch (ValidationException expected) {
      assertThat(expected.getMessage())
          .contains("Cannot find a mapping 'bbb' in 'TODO(bbb)' (/one.txt)");
    }

    // Does not conform the pattern for users
    write("one.txt", "// TODO(aaa foo/1234): Example\n");

    try {
      run(replace);
      fail();
    } catch (ValidationException expected) {
      assertThat(expected.getMessage())
          .contains("Unexpected 'aaa foo/1234' doesn't match expected format");
    }
  }

  @Test
  public void testMapOrDefault() throws Exception {
    TodoReplace replace = todoReplace(
        "mapping = { 'aaa': 'foo'}",
        "mode = 'MAP_OR_DEFAULT'",
        "default = 'TEST'");

    write("one.txt", "// TODO(aaa, nonExistent): Example\n");
    run(replace);
    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO(foo, TEST): Example\n")
        .containsNoMoreFiles();

    // Does not conform the pattern, will be replaced by the default anyway
    write("one.txt", "// TODO(aaa foo/1234, nonExistent): Example\n");
    run(replace);
    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO( TEST): Example\n")
        .containsNoMoreFiles();
  }

  @Test
  public void testMapOrDefaultNoDuplicates() throws Exception {
    TodoReplace replace = todoReplace(
        "mapping = { 'aaa': 'foo'}",
        "mode = 'MAP_OR_DEFAULT'",
        "default = 'TEST'");

    write("one.txt", "// TODO(bbb, ccc): Example\n");
    run(replace);

    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO(TEST): Example\n")
        .containsNoMoreFiles();
  }

  @Test
  public void testUseDefault() throws Exception {
    TodoReplace replace = todoReplace(
        "mode = 'USE_DEFAULT'",
        "default = 'TEST'");

    write("one.txt", "// TODO(bbb, ccc): Example\n");
    run(replace);
    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO(TEST): Example\n")
        .containsNoMoreFiles();

    // Does not conform the pattern, will be replaced by default anyway
    write("one.txt", "// TODO(bbb, ccc foo/1234): Example\n");
    run(replace);
    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO(TEST): Example\n")
        .containsNoMoreFiles();
  }

  @Test
  public void testScrub() throws Exception {
    TodoReplace replace = todoReplace(
        "mode = 'SCRUB_NAMES'");

    write("one.txt", "// TODO(aaa, bbb): Example\n");
    run(replace);
    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO: Example\n")
        .containsNoMoreFiles();

    // Does not conform the pattern, will be scrubbed anyway
    write("one.txt", "// TODO(aaa, bbb foo/1234): Example\n");
    run(replace);
    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO: Example\n")
        .containsNoMoreFiles();
  }

  @Test
  public void testTags() throws Exception {
    TodoReplace replace = todoReplace("mapping = { 'aaa': 'foo'}");

    write("one.txt", ""
        + "// TODO(aaa): Example\n"
        + "// NOTE(aaa): Example\n");
    run(replace);

    assertThatPath(checkoutDir)
        .containsFile("one.txt", ""
            + "// TODO(foo): Example\n"
            + "// NOTE(foo): Example\n")
        .containsNoMoreFiles();

    replace = todoReplace(
        "mapping = { 'aaa': 'foo'}",
        "tags = ['NOTE']");

    write("one.txt", ""
        + "// TODO(aaa): Example\n"
        + "// NOTE(aaa): Example\n");
    run(replace);

    assertThatPath(checkoutDir)
        .containsFile("one.txt", ""
            + "// TODO(aaa): Example\n"
            + "// NOTE(foo): Example\n")
        .containsNoMoreFiles();
  }

  @Test
  public void testPaths() throws Exception {
    TodoReplace replace = todoReplace(
        "mapping = { 'aaa': 'foo'}",
        "paths = glob(['**.cc'])");

    write("one.txt", "// TODO(aaa): Example\n");
    write("two.cc", "// TODO(aaa): Example\n");
    run(replace);

    assertThatPath(checkoutDir)
        .containsFile("one.txt", "// TODO(aaa): Example\n")
        .containsFile("two.cc", "// TODO(foo): Example\n")
        .containsNoMoreFiles();
  }

  private TransformWork run(Transformation replace) throws IOException, ValidationException {
    TransformWork work = TransformWorks.of(checkoutDir, "testmsg", console);
    replace.transform(work);
    return work;
  }

  private TodoReplace todoReplace(String... lines) throws ValidationException {
    return skylark.eval("r",
        "r = core.todo_replace(\n    " + Joiner.on(",\n    ").join(lines) + "\n)");
  }

  private void write(String path, String text) throws IOException {
    Files.write(checkoutDir.resolve(path), text.getBytes(UTF_8));
  }
}
