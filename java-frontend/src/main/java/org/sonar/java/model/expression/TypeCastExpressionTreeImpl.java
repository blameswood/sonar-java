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
package org.sonar.java.model.expression;

import org.apache.commons.lang.Validate;
import org.sonar.java.ast.parser.BoundListTreeImpl;
import org.sonar.java.model.AbstractTypedTree;
import org.sonar.java.model.InternalSyntaxToken;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ListTree;
import org.sonar.plugins.java.api.tree.SyntaxToken;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TreeVisitor;
import org.sonar.plugins.java.api.tree.TypeCastTree;
import org.sonar.plugins.java.api.tree.TypeTree;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TypeCastExpressionTreeImpl extends AbstractTypedTree implements TypeCastTree {

  private InternalSyntaxToken openParenToken;
  private final TypeTree type;
  @Nullable
  private final InternalSyntaxToken andToken;
  private final ListTree<Tree> bounds;
  private final InternalSyntaxToken closeParenToken;
  private final ExpressionTree expression;

  public TypeCastExpressionTreeImpl(TypeTree type, InternalSyntaxToken closeParenToken, ExpressionTree expression) {
    super(Kind.TYPE_CAST);
    this.type = Objects.requireNonNull(type);
    this.bounds = BoundListTreeImpl.emptyList();
    this.closeParenToken = closeParenToken;
    this.expression = Objects.requireNonNull(expression);
    andToken = null;
  }

  public TypeCastExpressionTreeImpl(TypeTree type, InternalSyntaxToken andToken, ListTree<Tree> bounds, InternalSyntaxToken closeParenToken, ExpressionTree expression) {
    super(Kind.TYPE_CAST);
    this.type = Objects.requireNonNull(type);
    this.bounds = bounds;
    this.closeParenToken = closeParenToken;
    this.expression = Objects.requireNonNull(expression);
    this.andToken = andToken;
  }

  public TypeCastExpressionTreeImpl complete(InternalSyntaxToken openParenToken) {
    Validate.isTrue(this.openParenToken == null && closeParenToken != null);
    this.openParenToken = openParenToken;

    return this;
  }

  @Override
  public Kind kind() {
    return Kind.TYPE_CAST;
  }

  @Override
  public SyntaxToken openParenToken() {
    return openParenToken;
  }

  @Override
  public TypeTree type() {
    return type;
  }

  @Nullable
  @Override
  public SyntaxToken andToken() {
    return andToken;
  }

  @Override
  public ListTree<Tree> bounds() {
    return bounds;
  }

  @Override
  public SyntaxToken closeParenToken() {
    return closeParenToken;
  }

  @Override
  public ExpressionTree expression() {
    return expression;
  }

  @Override
  public void accept(TreeVisitor visitor) {
    visitor.visitTypeCast(this);
  }

  @Override
  public Iterable<Tree> children() {
    List<Tree> res = new ArrayList<>();
    res.add(openParenToken);
    res.add(type);
    if (andToken != null) {
      res.add(andToken);
    }
    res.add(bounds);
    res.add(closeParenToken);
    res.add(expression);
    return res;
  }

}
