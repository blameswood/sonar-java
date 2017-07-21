/*
 * SonarQube Java
 * Copyright (C) 2012-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.se.checks;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import org.sonar.check.Rule;
import org.sonar.java.JavaVersionAwareVisitor;
import org.sonar.java.cfg.CFG;
import org.sonar.java.matcher.MethodMatcher;
import org.sonar.java.matcher.TypeCriteria;
import org.sonar.java.se.CheckerContext;
import org.sonar.java.se.ExplodedGraph;
import org.sonar.java.se.ExplodedGraph.Node;
import org.sonar.java.se.FlowComputation;
import org.sonar.java.se.LearnedConstraint;
import org.sonar.java.se.ProgramState;
import org.sonar.java.se.constraint.ObjectConstraint;
import org.sonar.java.se.symbolicvalues.SymbolicValue;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.JavaVersion;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Tree;

import javax.annotation.CheckForNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Rule(key = "S3824")
public class MapComputeIfAbsentOrPresentCheck extends SECheck implements JavaVersionAwareVisitor {

  private static final MethodMatcher MAP_GET = mapMethod("get", TypeCriteria.anyType());
  private static final MethodMatcher MAP_PUT = mapMethod("put", TypeCriteria.anyType(), TypeCriteria.anyType());

  private final Multimap<SymbolicValue, MapGetInvocation> mapGetInvocations = LinkedListMultimap.create();
  private final Multimap<SymbolicValue, ObjectConstraint> valuesUsedWithConstraints = HashMultimap.create();
  private final List<CheckIssue> checkIssues = new ArrayList<>();

  @Override
  public boolean isCompatibleWithJavaVersion(JavaVersion version) {
    return version.isJava8Compatible();
  }

  @Override
  public void init(MethodTree methodTree, CFG cfg) {
    mapGetInvocations.clear();
    valuesUsedWithConstraints.clear();
    checkIssues.clear();
  }

  private static MethodMatcher mapMethod(String methodName, TypeCriteria... parameterTypes) {
    return MethodMatcher.create().typeDefinition(TypeCriteria.subtypeOf("java.util.Map")).name(methodName).parameters(parameterTypes);
  }

  @Override
  public ProgramState checkPostStatement(CheckerContext context, Tree syntaxNode) {
    ProgramState ps = context.getState();

    if (syntaxNode.is(Tree.Kind.METHOD_INVOCATION)) {
      MethodInvocationTree mit = (MethodInvocationTree) syntaxNode;
      if (MAP_GET.matches(mit)) {
        ProgramState psBeforeInvocation = context.getNode().programState;
        ProgramState psAfterInvocation = ps;

        SymbolicValue keySV = psBeforeInvocation.peekValue(0);
        SymbolicValue mapSV = psBeforeInvocation.peekValue(1);
        SymbolicValue valueSV = psAfterInvocation.peekValue();

        mapGetInvocations.put(mapSV, new MapGetInvocation(valueSV, keySV, mit));
      }
    } else if (syntaxNode.is(Tree.Kind.IDENTIFIER)) {
      SymbolicValue peekValue = ps.peekValue();
      mapGetInvocations.values().forEach(mapGetInvocation -> {
        if (mapGetInvocation.value.equals(peekValue)) {
          ObjectConstraint constraint = getObjectConstraint(ps, peekValue);
          if (constraint != null) {
            valuesUsedWithConstraints.put(peekValue, constraint);
          }
        }
      });
    }
    return super.checkPostStatement(context, syntaxNode);
  }

  @Override
  public ProgramState checkPreStatement(CheckerContext context, Tree syntaxNode) {
    if (syntaxNode.is(Tree.Kind.METHOD_INVOCATION)) {
      MethodInvocationTree mit = (MethodInvocationTree) syntaxNode;
      if (MAP_PUT.matches(mit)) {
        ProgramState ps = context.getState();

        SymbolicValue keySV = ps.peekValue(1);
        SymbolicValue mapSV = ps.peekValue(2);
        mapGetInvocations.get(mapSV).stream()
          .filter(getOnSameMap -> getOnSameMap.withSameKey(keySV))
          .findAny()
          .ifPresent(getOnSameMap -> {
            ObjectConstraint constraint = getObjectConstraint(ps, getOnSameMap.value);
            if (constraint != null && isInsideIfStatementWithNullCheckWithoutElse(mit, context.getNode(), getOnSameMap.value)) {
              checkIssues.add(new CheckIssue(context.getNode(), getOnSameMap.mit, mit, getOnSameMap.value, constraint));
            }
          });
      }
    }
    return super.checkPreStatement(context, syntaxNode);
  }

  private static boolean isInsideIfStatementWithNullCheckWithoutElse(MethodInvocationTree mit, ExplodedGraph.Node startingNode, SymbolicValue value) {
    Tree parent = mit.parent();
    while (parent != null && !parent.is(Tree.Kind.IF_STATEMENT)) {
      parent = parent.parent();
    }
    if (parent == null) {
      return false;
    }
    IfStatementTree ifStatementTree = (IfStatementTree) parent;
    return ifStatementTree.elseStatement() == null && isLearningConstraintOnIfStatement(ifStatementTree, startingNode, value);
  }


  private static boolean isLearningConstraintOnIfStatement(IfStatementTree ifStatementTree, Node startingNode, SymbolicValue value) {
    // check that the syntax tree associated with the learnconstraint on value is part of the ifStatement condition
    return true;
  }

  private static boolean learnedConstraintForValue(ExplodedGraph.Node node, SymbolicValue value) {
    return node.edges().stream()
      .anyMatch(edge -> {
        Set<LearnedConstraint> learnedConstraints = edge.learnedConstraints();
        return learnedConstraints.stream().anyMatch(lc -> lc.symbolicValue() == value);
      });
  }

  @Override
  public void checkEndOfExecution(CheckerContext context) {
    checkIssues.stream()
      .filter(CheckIssue::isOnlyPossibleIssueForReportTree)
      .filter(CheckIssue::isAlwaysUsedWithSameConstraint)
      .forEach(issue -> issue.report(context));
  }

  @CheckForNull
  private static ObjectConstraint getObjectConstraint(ProgramState ps, SymbolicValue sv) {
    return ps.getConstraint(sv, ObjectConstraint.class);
  }

  private class CheckIssue {
    private final ExplodedGraph.Node node;

    private final MethodInvocationTree getInvocation;
    private final MethodInvocationTree putInvocation;

    private final SymbolicValue value;
    private final ObjectConstraint valueConstraint;

    private CheckIssue(Node node, MethodInvocationTree getInvocation, MethodInvocationTree putInvocation, SymbolicValue value, ObjectConstraint valueConstraint) {
      this.node = node;

      this.getInvocation = getInvocation;
      this.putInvocation = putInvocation;

      this.value = value;
      this.valueConstraint = valueConstraint;
    }

    private boolean isOnlyPossibleIssueForReportTree() {
      return checkIssues.stream().noneMatch(this::differentIssueOnSameTree);
    }

    private boolean differentIssueOnSameTree(CheckIssue otherIssue) {
      return this != otherIssue && getInvocation.equals(otherIssue.getInvocation) && valueConstraint != otherIssue.valueConstraint;
    }

    private boolean isAlwaysUsedWithSameConstraint() {
      Collection<ObjectConstraint> valueUsedWithConstraints = valuesUsedWithConstraints.get(value);
      return valueUsedWithConstraints.isEmpty() || (valueUsedWithConstraints.size() == 1 && valueUsedWithConstraints.contains(valueConstraint));
    }

    private void report(CheckerContext context) {
      context.reportIssue(getInvocation, MapComputeIfAbsentOrPresentCheck.this, issueMsg(), flows());
    }

    private String issueMsg() {
      return String.format("Replace this \"Map.get()\" and condition with a call to \"Map.%s()\".",
        valueConstraint == ObjectConstraint.NULL ? "computeIfAbsent" : "computeIfPresent");
    }

    private Set<List<JavaFileScannerContext.Location>> flows() {
      // build nullness flows for value constraint
      Set<List<JavaFileScannerContext.Location>> flows = FlowComputation.flow(node, value, Collections.singletonList(ObjectConstraint.class));
      // enrich each flow with both map method invocations
      return flows.stream().map(flow -> {
        List<JavaFileScannerContext.Location> newFlow = new ArrayList<>(flow);
        newFlow.add(new JavaFileScannerContext.Location("'Map.get()' is invoked.", getInvocation.methodSelect()));
        newFlow.add(0, new JavaFileScannerContext.Location("'Map.put()' is invoked with same key.", putInvocation.methodSelect()));
        return Collections.unmodifiableList(newFlow);
      }).collect(Collectors.toSet());
    }
  }

  private static class MapGetInvocation {
    private final SymbolicValue value;
    private final SymbolicValue key;
    private final MethodInvocationTree mit;

    private MapGetInvocation(SymbolicValue value, SymbolicValue key, MethodInvocationTree mit) {
      this.value = value;
      this.key = key;
      this.mit = mit;
    }

    private boolean withSameKey(SymbolicValue key) {
      return this.key.equals(key);
    }
  }
}
