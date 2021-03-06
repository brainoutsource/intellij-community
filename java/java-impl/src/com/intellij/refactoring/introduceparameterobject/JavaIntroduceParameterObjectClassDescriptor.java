/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.refactoring.introduceparameterobject;

import com.intellij.codeInsight.generation.GenerateMembersUtil;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.util.PackageUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.refactoring.MoveDestination;
import com.intellij.refactoring.changeSignature.ParameterInfoImpl;
import com.intellij.refactoring.introduceParameterObject.IntroduceParameterObjectClassDescriptor;
import com.intellij.refactoring.introduceParameterObject.IntroduceParameterObjectDelegate;
import com.intellij.util.Function;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class JavaIntroduceParameterObjectClassDescriptor extends IntroduceParameterObjectClassDescriptor<PsiMethod, ParameterInfoImpl> {
  private static final Logger LOG = Logger.getInstance("#" + JavaIntroduceParameterObjectClassDescriptor.class.getName());
  private final Set<PsiTypeParameter> myTypeParameters = new HashSet<>();
  private final Map<ParameterInfoImpl, ParameterBean> myExistingClassProperties = new HashMap<>();
  private final MoveDestination myMoveDestination;

  private PsiMethod myExistingClassCompatibleConstructor;

  public JavaIntroduceParameterObjectClassDescriptor(String className,
                                                     String packageName,
                                                     MoveDestination moveDestination,
                                                     boolean useExistingClass,
                                                     boolean createInnerClass,
                                                     String newVisibility,
                                                     ParameterInfoImpl[] paramsToMerge,
                                                     PsiMethod method, boolean generateAccessors) {
    super(className, calcPackageName(packageName, createInnerClass, method), useExistingClass, createInnerClass,
          newVisibility, generateAccessors, paramsToMerge);
    myMoveDestination = moveDestination;
    final PsiTypeVisitor<Object> typeParametersVisitor = new PsiTypeVisitor<Object>() {
      @Override
      public Object visitClassType(PsiClassType classType) {
        final PsiClass referent = classType.resolve();
        if (referent instanceof PsiTypeParameter) {
          myTypeParameters.add((PsiTypeParameter)referent);
        }
        for (PsiType type : classType.getParameters()) {
          type.accept(this);
        }

        return super.visitClassType(classType);
      }
    };
    for (ParameterInfoImpl parameterInfo : paramsToMerge) {
      parameterInfo.getTypeWrapper().getType(method, method.getManager()).accept(typeParametersVisitor);
    }
  }

  private static String calcPackageName(String packageName, boolean createInnerClass, PsiMethod method) {
    if (createInnerClass) {
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass != null) {
        final String qualifiedName = containingClass.getQualifiedName();
        return qualifiedName != null ? qualifiedName : "";
      }
      else {
        return packageName;
      }
    }
    return packageName;
  }

  public Set<PsiTypeParameter> getTypeParameters() {
    return myTypeParameters;
  }

  public MoveDestination getMoveDestination() {
    return myMoveDestination;
  }

  public String createFakeClassTypeText() {
    String text = StringUtil.getQualifiedName(getPackageName(), getClassName());
    if (!myTypeParameters.isEmpty()) {
      text += "<" + StringUtil.join(myTypeParameters, new Function<PsiTypeParameter, String>() {
        @Override
        public String fun(PsiTypeParameter parameter) {
          return parameter.getName();
        }
      }, ", ") + ">";
    }
    return text;
  }

  public PsiMethod getExistingClassCompatibleConstructor() {
    return myExistingClassCompatibleConstructor;
  }

  public void setExistingClassCompatibleConstructor(PsiMethod existingClassCompatibleConstructor) {
    myExistingClassCompatibleConstructor = existingClassCompatibleConstructor;
  }

  @Override
  public PsiClass getExistingClass() {
    return (PsiClass)super.getExistingClass();
  }

  @Override
  public void setExistingClass(PsiElement existingClass) {
    super.setExistingClass(existingClass);
    if (isUseExistingClass()) {
      setExistingClassCompatibleConstructor(existingClassIsCompatible((PsiClass)existingClass));
    }
  }

  public String getGetter(ParameterInfoImpl param) {
    final ParameterBean bean = getBean(param);
    return bean != null ? bean.getGetter() : null;
  }

  public String getSetter(ParameterInfoImpl param) {
    final ParameterBean bean = getBean(param);
    return bean != null ? bean.getSetter() : null;
  }

  @Override
  public String getSetterName(ParameterInfoImpl parameterInfo, PsiElement context) {
    final ParameterBean bean = getBean(parameterInfo);
    @NonNls String setter = bean != null ? bean.getSetter() : null;
    if (setter == null) {
      setter = bean != null && bean.getField() != null
               ? GenerateMembersUtil.suggestSetterName(bean.getField())
               : GenerateMembersUtil
                 .suggestSetterName(parameterInfo.getName(), parameterInfo.getTypeWrapper().getType(context, context.getManager()),
                                    context.getProject());
    }

    return setter;
  }

  @Override
  public String getGetterName(ParameterInfoImpl paramInfo, PsiElement context) {
    final ParameterBean bean = getBean(paramInfo);
    @NonNls String getter = bean != null ? bean.getGetter() : null;
    if (getter == null) {
      getter = bean != null && bean.getField() != null ? GenerateMembersUtil.suggestGetterName(bean.getField())
                                                       : GenerateMembersUtil
                 .suggestGetterName(paramInfo.getName(), paramInfo.getTypeWrapper().getType(context, context.getManager()),
                                    context.getProject());
    }
    return getter;
  }

  @Override
  public void initExistingClass(PsiMethod method) {
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(method.getProject());
    final String qualifiedName = StringUtil.getQualifiedName(getPackageName(), getClassName());
    final PsiClass existingClass = psiFacade.findClass(qualifiedName, method.getResolveScope());
    setExistingClass(existingClass);
  }

  @Nullable
  public PsiField getField(ParameterInfoImpl parameter) {
    final ParameterBean bean = getBean(parameter);
    return bean != null ? bean.getField() : null;
  }

  private ParameterBean getBean(ParameterInfoImpl param) {
    return myExistingClassProperties.get(param);
  }

  @Nullable
  private PsiMethod existingClassIsCompatible(@NotNull PsiClass aClass) {
    ParameterInfoImpl[] paramsToMerge = getParamsToMerge();
    if (paramsToMerge.length == 1) {
      final ParameterInfoImpl parameterInfo = paramsToMerge[0];
      final PsiType paramType = parameterInfo.getTypeWrapper().getType(aClass, aClass.getManager());
      if (TypeConversionUtil.isPrimitiveWrapper(aClass.getQualifiedName())) {
        ParameterBean bean = new ParameterBean();
        bean.setField(aClass.findFieldByName("value", false));
        bean.setGetter(paramType.getCanonicalText() + "Value");
        myExistingClassProperties.put(parameterInfo, bean);
        for (PsiMethod constructor : aClass.getConstructors()) {
          if (constructorIsCompatible(constructor, new ParameterInfoImpl[]{parameterInfo}, aClass)) return constructor;
        }
      }
    }
    final PsiMethod[] constructors = aClass.getConstructors();
    PsiMethod compatibleConstructor = null;
    for (PsiMethod constructor : constructors) {
      if (constructorIsCompatible(constructor, paramsToMerge, aClass)) {
        compatibleConstructor = constructor;
        break;
      }
    }
    if (compatibleConstructor == null) {
      return null;
    }
    final PsiParameterList parameterList = compatibleConstructor.getParameterList();
    final PsiParameter[] constructorParams = parameterList.getParameters();
    for (int i = 0; i < constructorParams.length; i++) {
      final PsiParameter param = constructorParams[i];
      final ParameterInfoImpl methodParam = getParameterInfo(i);
      final ParameterBean bean = new ParameterBean();
      myExistingClassProperties.put(methodParam, bean);

      final PsiField field = findFieldAssigned(param, compatibleConstructor);
      if (field == null) {
        return null;
      }

      bean.setField(field);

      final PsiMethod getterForField = PropertyUtil.findGetterForField(field);
      if (getterForField != null) {
        bean.setGetter(getterForField.getName());
      }

      final PsiMethod setterForField = PropertyUtil.findSetterForField(field);
      if (setterForField != null) {
        bean.setSetter(setterForField.getName());
      }
    }
    return compatibleConstructor;
  }

  private boolean constructorIsCompatible(PsiMethod constructor, ParameterInfoImpl[] paramsToMerge, PsiElement context) {
    final PsiParameterList parameterList = constructor.getParameterList();
    final PsiParameter[] constructorParams = parameterList.getParameters();
    if (constructorParams.length != paramsToMerge.length) {
      return false;
    }
    for (int i = 0; i < constructorParams.length; i++) {
      if (!TypeConversionUtil.isAssignable(constructorParams[i].getType(),
                                           getParameterInfo(i).getTypeWrapper().getType(context, context.getManager()))) {
        return false;
      }
    }
    return true;
  }

  private static PsiField findFieldAssigned(PsiParameter param, PsiMethod constructor) {
    final ParamAssignmentFinder visitor = new ParamAssignmentFinder(param);
    constructor.accept(visitor);
    return visitor.getFieldAssigned();
  }

  @Override
  public PsiClass createClass(PsiMethod method, IntroduceParameterObjectDelegate.Accessor[] accessors) {
    if (isUseExistingClass()) {
      return getExistingClass();
    }

    final ParameterObjectBuilder beanClassBuilder = new ParameterObjectBuilder();
    beanClassBuilder.setVisibility(isCreateInnerClass() ? PsiModifier.PRIVATE : PsiModifier.PUBLIC);
    beanClassBuilder.setProject(method.getProject());
    beanClassBuilder.setTypeArguments(getTypeParameters());
    beanClassBuilder.setClassName(getClassName());
    beanClassBuilder.setPackageName(getPackageName());
    PsiParameter[] parameters = method.getParameterList().getParameters();
    final ParameterInfoImpl[] parameterInfos = getParamsToMerge();
    for (int i = 0; i < parameterInfos.length; i++) {
      PsiParameter parameter = parameters[parameterInfos[i].getOldIndex()];
      final boolean setterRequired = accessors[i] == IntroduceParameterObjectDelegate.Accessor.Setter;
      final String newName = parameterInfos[i].getName();
      beanClassBuilder
        .addField(parameter, newName, parameterInfos[i].getTypeWrapper().getType(method, method.getManager()), setterRequired);
    }

    final String classString = beanClassBuilder.buildBeanClass();

    try {
      final PsiFileFactory factory = PsiFileFactory.getInstance(method.getProject());
      final PsiJavaFile newFile =
        (PsiJavaFile)factory.createFileFromText(getClassName() + ".java", JavaFileType.INSTANCE, classString);
      if (isCreateInnerClass()) {
        final PsiClass containingClass = method.getContainingClass();
        final PsiClass[] classes = newFile.getClasses();
        assert classes.length > 0 : classString;
        final PsiClass innerClass = (PsiClass)containingClass.add(classes[0]);
        PsiUtil.setModifierProperty(innerClass, PsiModifier.STATIC, true);
        return (PsiClass)JavaCodeStyleManager.getInstance(newFile.getProject()).shortenClassReferences(innerClass);
      }
      else {
        final PsiFile containingFile = method.getContainingFile();
        final PsiDirectory containingDirectory = containingFile.getContainingDirectory();
        final PsiDirectory directory;
        final MoveDestination moveDestination = getMoveDestination();
        if (moveDestination != null) {
          directory = moveDestination.getTargetDirectory(containingDirectory);
        }
        else {
          final Module module = ModuleUtilCore.findModuleForPsiElement(containingFile);
          directory =
            PackageUtil.findOrCreateDirectoryForPackage(module, getPackageName(), containingDirectory, true, true);
        }

        if (directory != null) {

          final CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(method.getManager().getProject());
          final PsiElement shortenedFile = JavaCodeStyleManager.getInstance(newFile.getProject()).shortenClassReferences(newFile);
          final PsiElement reformattedFile = codeStyleManager.reformat(shortenedFile);
          return ((PsiJavaFile)directory.add(reformattedFile)).getClasses()[0];
        }
      }
    }
    catch (IncorrectOperationException e) {
      LOG.info(e);
    }
    return null;
  }


  private static class ParamAssignmentFinder extends JavaRecursiveElementWalkingVisitor {

    private final PsiParameter param;

    private PsiField fieldAssigned = null;

    ParamAssignmentFinder(PsiParameter param) {
      this.param = param;
    }

    public void visitAssignmentExpression(PsiAssignmentExpression assignment) {
      super.visitAssignmentExpression(assignment);
      final PsiExpression lhs = assignment.getLExpression();
      final PsiExpression rhs = assignment.getRExpression();
      if (!(lhs instanceof PsiReferenceExpression)) {
        return;
      }
      if (!(rhs instanceof PsiReferenceExpression)) {
        return;
      }
      final PsiElement referent = ((PsiReference)rhs).resolve();
      if (referent == null || !referent.equals(param)) {
        return;
      }
      final PsiElement assigned = ((PsiReference)lhs).resolve();
      if (assigned == null || !(assigned instanceof PsiField)) {
        return;
      }
      fieldAssigned = (PsiField)assigned;
    }

    public PsiField getFieldAssigned() {
      return fieldAssigned;
    }
  }

  private static class ParameterBean {
    private PsiField myField;
    private String myGetter;
    private String mySetter;

    public PsiField getField() {
      return myField;
    }

    public void setField(PsiField field) {
      myField = field;
    }

    public String getGetter() {
      return myGetter;
    }

    public void setGetter(String getter) {
      myGetter = getter;
    }

    public String getSetter() {
      return mySetter;
    }

    public void setSetter(String setter) {
      mySetter = setter;
    }
  }
}
