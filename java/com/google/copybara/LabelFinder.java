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

package com.google.copybara;

import com.google.common.base.Preconditions;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;

/**
 * A simple line finder/parser for labels like:
 * <ul>
 *   <li>foo = bar</li>
 *   <li>baz : foo</li>
 * </ul>
 *
 * <p>In general this class should only be used in {@code Origin}s to create a labels map.
 * During transformations/destination, it can be used to check if a line is a line but
 * never to find labels. Use {@link TransformWork#getLabel(String)} instead, since it looks
 * in more places for labels.
 *
 * TODO(malcon): Rename to MaybeLabel
 */
public class LabelFinder {

  private static final String VALID_LABEL_EXPR = "([\\w-]+)";

  public static final Pattern VALID_LABEL = Pattern.compile(VALID_LABEL_EXPR);

  private static final Pattern URL = Pattern.compile(VALID_LABEL + "://.*");

  private static final Pattern LABEL_PATTERN = Pattern.compile(
      "^" + VALID_LABEL_EXPR + "( *[:=] ?)(.*)");
  private final Matcher matcher;
  private final String line;

  public LabelFinder(String line) {
    matcher = LABEL_PATTERN.matcher(line);
    this.line = line;
  }

  public boolean isLabel() {
    // It is a line if it looks like a line but it doesn't look like a url (foo://bar)
    return matcher.matches() && !URL.matcher(line).matches();
  }

  public boolean isLabel(String labelName) {
    return isLabel() && getName().equals(labelName);
  }

  public String getName() {
    checkIsLabel();
    return matcher.group(1);
  }

  public String getSeparator() {
    checkIsLabel();
    return matcher.group(2);
  }

  public String getValue() {
    checkIsLabel();
    return matcher.group(3);
  }

  private void checkIsLabel() {
    Preconditions.checkState(isLabel(), "Not a label: '" + line + "'");
  }

  public String getLine() {
    return line;
  }
}
