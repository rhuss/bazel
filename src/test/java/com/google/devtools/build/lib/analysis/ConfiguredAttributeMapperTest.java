// Copyright 2015 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.testutil.MoreAsserts.assertSameContents;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.syntax.Label;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link ConfiguredAttributeMapper}.
 *
 * <p>This is distinct from {@link
 * com.google.devtools.build.lib.analysis.select.ConfiguredAttributeMapperCommonTest} because the
 * latter needs to inherit from {@link
 * com.google.devtools.build.lib.analysis.select.AbstractAttributeMapperTest} to run tests common to
 * all attribute mappers.
 */
public class ConfiguredAttributeMapperTest extends BuildViewTestCase {

  /**
   * Returns a ConfiguredAttributeMapper bound to the given rule with the target configuration.
   */
  private ConfiguredAttributeMapper getMapper(String label) throws Exception {
    return ConfiguredAttributeMapper.of(
        (RuleConfiguredTarget) getConfiguredTarget(label));
  }

  private void writeConfigRules() throws Exception {
    scratch.file("conditions/BUILD",
        "config_setting(",
        "    name = 'a',",
        "    values = {'compilation_mode': 'opt'})",
        "config_setting(",
        "    name = 'b',",
        "    values = {'compilation_mode': 'dbg'})");
  }

  /**
   * Tests that {@link ConfiguredAttributeMapper#get} only gets the configuration-appropriate
   * value.
   */
  public void testGetAttribute() throws Exception {
    writeConfigRules();
    scratch.file("a/BUILD",
        "genrule(",
        "    name = 'gen',",
        "    srcs = [],",
        "    outs = ['out'],",
        "    cmd = select({",
        "        '//conditions:a': 'a command',",
        "        '//conditions:b': 'b command',",
        "        '" + Type.Selector.DEFAULT_CONDITION_KEY + "': 'default command',",
        "    }))");

    useConfiguration("-c", "opt");
    assertEquals("a command", getMapper("//a:gen").get("cmd", Type.STRING));

    useConfiguration("-c", "dbg");
    assertEquals("b command", getMapper("//a:gen").get("cmd", Type.STRING));

    useConfiguration("-c", "fastbuild");
    assertEquals("default command", getMapper("//a:gen").get("cmd", Type.STRING));
  }

  /**
   * Tests that label visitation only travels down configuration-appropriate paths.
   */
  public void testLabelVisitation() throws Exception {
    writeConfigRules();
    scratch.file("a/BUILD",
        "sh_binary(",
        "    name = 'bin',",
        "    srcs = ['bin.sh'],",
        "    deps = select({",
        "        '//conditions:a': [':adep'],",
        "        '//conditions:b': [':bdep'],",
        "        '" + Type.Selector.DEFAULT_CONDITION_KEY + "': [':defaultdep'],",
        "    }))",
        "sh_library(",
        "    name = 'adep',",
        "    srcs = ['adep.sh'])",
        "sh_library(",
        "    name = 'bdep',",
        "    srcs = ['bdep.sh'])",
        "sh_library(",
        "    name = 'defaultdep',",
        "    srcs = ['defaultdep.sh'])");

    final List<Label> visitedLabels = new ArrayList<>();
    AttributeMap.AcceptsLabelAttribute testVisitor =
        new AttributeMap.AcceptsLabelAttribute() {
          @Override
          public void acceptLabelAttribute(Label label, Attribute attribute) {
            if (label.toString().contains("//a:")) { // Ignore implicit common dependencies.
              visitedLabels.add(label);
            }
          }
        };

    final Label binSrc = Label.parseAbsolute("//a:bin.sh");

    useConfiguration("-c", "opt");
    getMapper("//a:bin").visitLabels(testVisitor);
    assertSameContents(ImmutableList.of(binSrc, Label.parseAbsolute("//a:adep")), visitedLabels);

    visitedLabels.clear();
    useConfiguration("-c", "dbg");
    getMapper("//a:bin").visitLabels(testVisitor);
    assertSameContents(ImmutableList.of(binSrc, Label.parseAbsolute("//a:bdep")), visitedLabels);

    visitedLabels.clear();
    useConfiguration("-c", "fastbuild");
    getMapper("//a:bin").visitLabels(testVisitor);
    assertSameContents(
        ImmutableList.of(binSrc, Label.parseAbsolute("//a:defaultdep")), visitedLabels);
  }

  /**
   * Tests that for configurable attributes where the *values* are evaluated in different
   * configurations, the configuration checking still uses the original configuration.
   */
  public void testConfigurationTransitions() throws Exception {
    writeConfigRules();
    scratch.file("a/BUILD",
        "genrule(",
        "    name = 'gen',",
        "    srcs = [],",
        "    outs = ['out'],",
        "    cmd = 'nothing',",
        "    tools = select({",
        "        '//conditions:a': [':adep'],",
        "        '//conditions:b': [':bdep'],",
        "        '" + Type.Selector.DEFAULT_CONDITION_KEY + "': [':defaultdep'],",
        "    }))",
        "sh_binary(",
        "    name = 'adep',",
        "    srcs = ['adep.sh'])",
        "sh_binary(",
        "    name = 'bdep',",
        "    srcs = ['bdep.sh'])",
        "sh_binary(",
        "    name = 'defaultdep',",
        "    srcs = ['defaultdep.sh'])");
    useConfiguration("-c", "dbg");

    // Target configuration is in dbg mode, so we should match //conditions:b:
    assertSameContents(
        ImmutableList.of(Label.parseAbsolute("//a:bdep")),
        getMapper("//a:gen").get("tools", Type.LABEL_LIST));

    // Verify the "tools" dep uses a different configuration that's not also in "dbg":
    assertEquals(Attribute.ConfigurationTransition.HOST,
        getTarget("//a:gen").getAssociatedRule().getRuleClassObject()
            .getAttributeByName("tools").getConfigurationTransition());
    assertEquals(CompilationMode.OPT, getHostConfiguration().getCompilationMode());
  }

  public void testConcatenatedSelects() throws Exception {
    scratch.file("hello/BUILD",
        "config_setting(name = 'a', values = {'define': 'foo=a'})",
        "config_setting(name = 'b', values = {'define': 'foo=b'})",
        "config_setting(name = 'c', values = {'define': 'bar=c'})",
        "config_setting(name = 'd', values = {'define': 'bar=d'})",
        "genrule(",
        "    name = 'gen',",
        "    srcs = select({':a': ['a.in'], ':b': ['b.in']})",
        "         + select({':c': ['c.in'], ':d': ['d.in']}),",
        "    outs = ['out'],",
        "    cmd = 'nothing',",
        ")");
    useConfiguration("--define", "foo=a", "--define", "bar=d");
    assertSameContents(
         ImmutableList.of(Label.parseAbsolute("//hello:a.in"), Label.parseAbsolute("//hello:d.in")),
         getMapper("//hello:gen").get("srcs", Type.LABEL_LIST));
  }
}
