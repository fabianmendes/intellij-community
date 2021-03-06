/*
 * Copyright 2005-2018 Bas Leijdekkers
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
package com.siyeh.ig.inheritance;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.PackageScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Query;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.psiutils.MethodCallUtils;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import com.siyeh.ig.psiutils.TrackingEquivalenceChecker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RedundantMethodOverrideInspection extends BaseInspection {

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message(
      "redundant.method.override.problem.descriptor");
  }

  @Override
  @Nullable
  protected InspectionGadgetsFix buildFix(Object... infos) {
    return new RedundantMethodOverrideFix();
  }

  private static class RedundantMethodOverrideFix
    extends InspectionGadgetsFix {

    @Override
    @NotNull
    public String getFamilyName() {
      return InspectionGadgetsBundle.message(
        "redundant.method.override.quickfix");
    }

    @Override
    public void doFix(Project project, ProblemDescriptor descriptor) {
      final PsiElement methodNameIdentifier = descriptor.getPsiElement();
      final PsiElement method = methodNameIdentifier.getParent();
      assert method != null;
      deleteElement(method);
    }
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new RedundantMethodOverrideVisitor();
  }

  private static class RedundantMethodOverrideVisitor extends BaseInspectionVisitor {
    @Override
    public void visitMethod(PsiMethod method) {
      super.visitMethod(method);
      if (method.isConstructor()) {
        return;
      }
      final PsiCodeBlock body = method.getBody();
      if (body == null || method.getNameIdentifier() == null) {
        return;
      }
      final PsiMethod[] methods = method.findSuperMethods();
      if (methods.length == 0) {
        return;
      }
      final PsiMethod superMethod = methods[0];
      if (superMethod.hasModifierProperty(PsiModifier.DEFAULT) && methods.length > 1) {
        return;
      }
      if (!AbstractMethodOverridesAbstractMethodInspection.methodsHaveSameAnnotationsAndModifiers(method, superMethod) ||
          !AbstractMethodOverridesAbstractMethodInspection.methodsHaveSameReturnTypes(method, superMethod) ||
          !AbstractMethodOverridesAbstractMethodInspection.haveSameExceptionSignatures(method, superMethod) ||
          method.isVarArgs() != superMethod.isVarArgs()) {
        return;
      }
      final PsiCodeBlock superBody = superMethod.getBody();

      final TrackingEquivalenceChecker checker = new TrackingEquivalenceChecker();
      final PsiParameter[] parameters = method.getParameterList().getParameters();
      final PsiParameter[] superParameters = superMethod.getParameterList().getParameters();
      for (int i = 0; i < parameters.length; i++) {
        final PsiParameter parameter = parameters[i];
        final PsiParameter superParameter = superParameters[i];
        checker.markDeclarationsAsEquivalent(parameter, superParameter);
      }
      checker.markDeclarationsAsEquivalent(method, superMethod);
      if (checker.codeBlocksAreEquivalent(body, superBody) || isSuperCallWithSameArguments(body, method, superMethod)) {
        registerMethodError(method);
      }
    }

    private boolean isSuperCallWithSameArguments(PsiCodeBlock body, PsiMethod method, PsiMethod superMethod) {
      final PsiStatement[] statements = body.getStatements();
      if (statements.length != 1) {
        return false;
      }
      final PsiStatement statement = statements[0];
      final PsiExpression expression;
      if (PsiType.VOID.equals(method.getReturnType())) {
        if (statement instanceof PsiExpressionStatement) {
          final PsiExpressionStatement expressionStatement = (PsiExpressionStatement)statement;
          expression = expressionStatement.getExpression();
        }
        else {
          return false;
        }
      }
      else {
        if (statement instanceof PsiReturnStatement) {
          final PsiReturnStatement returnStatement = (PsiReturnStatement)statement;
          expression = ParenthesesUtils.stripParentheses(returnStatement.getReturnValue());
        }
        else {
          return false;
        }
      }
      if (!(expression instanceof PsiMethodCallExpression)) {
        return false;
      }
      final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)expression;
      if (!MethodCallUtils.isSuperMethodCall(methodCallExpression, method)) return false;

      if (superMethod.hasModifierProperty(PsiModifier.PROTECTED)) {
        final PsiJavaFile file = (PsiJavaFile)method.getContainingFile();
        // implementing a protected method in another package makes it available to that package.
        PsiPackage aPackage = JavaPsiFacade.getInstance(method.getProject()).findPackage(file.getPackageName());
        if (aPackage == null) {
          return false; // when package statement is incorrect
        }
        final PackageScope scope = new PackageScope(aPackage, false, false);
        if (isOnTheFly()) {
          final PsiSearchHelper searchHelper = PsiSearchHelper.getInstance(method.getProject());
          final PsiSearchHelper.SearchCostResult cost =
            searchHelper.isCheapEnoughToSearch(method.getName(), scope, null, null);
          if (cost == PsiSearchHelper.SearchCostResult.ZERO_OCCURRENCES) {
            return true;
          }
          if (cost == PsiSearchHelper.SearchCostResult.TOO_MANY_OCCURRENCES) {
            return false;
          }
        }
        final Query<PsiReference> search = ReferencesSearch.search(method, scope);
        final PsiClass containingClass = method.getContainingClass();
        for (PsiReference reference : search) {
          if (!PsiTreeUtil.isAncestor(containingClass, reference.getElement(), true)) {
            return false;
          }
        }
      }

      return areSameArguments(methodCallExpression, method);
    }

    private static boolean areSameArguments(PsiMethodCallExpression methodCallExpression, PsiMethod method) {
      // void foo(int param) { super.foo(42); } is not redundant
      PsiExpression[] arguments = methodCallExpression.getArgumentList().getExpressions();
      PsiParameter[] parameters = method.getParameterList().getParameters();
      if (arguments.length != parameters.length) return false;
      for (int i = 0; i < arguments.length; i++) {
        PsiExpression argument = arguments[i];
        PsiExpression exp = PsiUtil.deparenthesizeExpression(argument);
        if (!(exp instanceof PsiReferenceExpression)) return false;
        PsiElement resolved = ((PsiReferenceExpression)exp).resolve();
        if (!method.getManager().areElementsEquivalent(parameters[i], resolved)) {
          return false;
        }
      }
      return true;
    }
  }
}