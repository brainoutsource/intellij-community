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
package com.intellij.psi.augment;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * An extension that enables plugins to alter a behavior of Java PSI elements.
 * During indexing, only {@link DumbAware} augment providers are run.
 */
public abstract class PsiAugmentProvider {
  public static final ExtensionPointName<PsiAugmentProvider> EP_NAME = ExtensionPointName.create("com.intellij.lang.psiAugmentProvider");

  //<editor-fold desc="Methods to override in implementations.">

  /**
   * An extension that enables one to add children to some PSI elements, e.g. methods to Java classes.
   * The class code remains the same, but its method accessors also include the results returned from {@link PsiAugmentProvider}s.
   */
  @NotNull
  protected <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element, @NotNull Class<Psi> type) {
    return Collections.emptyList();
  }

  /**
   * Extends {@link PsiTypeElement#getType()} so type could be retrieved from external place
   * e.g. from variable initializer in lombok case (http://projectlombok.org/features/val.html)
   *
   * @param typeElement place where inference takes place,
   *                    also nested PsiTypeElement-s (e.g. for List<String> PsiTypeElements corresponding to both List and String would be suggested)
   * @return inferred type or null, if inference is not applicable
   * @since 14.1
   */
  @Nullable
  protected PsiType inferType(@NotNull PsiTypeElement typeElement) {
    return null;
  }

  //</editor-fold>

  //<editor-fold desc="API and the inner kitchen.">

  @NotNull
  public static <Psi extends PsiElement> List<Psi> collectAugments(@NotNull final PsiElement element, @NotNull final Class<Psi> type) {
    final List<Psi> result = ContainerUtil.newSmartList();

    forEach(element.getProject(), new Processor<PsiAugmentProvider>() {
      @Override
      public boolean process(PsiAugmentProvider provider) {
        result.addAll(provider.getAugments(element, type));
        return true;
      }
    });

    return result;
  }

  @Nullable
  public static PsiType getInferredType(@NotNull final PsiTypeElement typeElement) {
    final Ref<PsiType> result = Ref.create();

    forEach(typeElement.getProject(), new Processor<PsiAugmentProvider>() {
      @Override
      public boolean process(PsiAugmentProvider provider) {
        PsiType type = provider.inferType(typeElement);
        if (type != null) {
          result.set(type);
          return false;
        }
        else {
          return true;
        }
      }
    });

    return result.get();
  }

  private static void forEach(Project project, Processor<PsiAugmentProvider> processor) {
    for (PsiAugmentProvider provider : Extensions.getExtensions(EP_NAME)) {
      if (!DumbService.isDumb(project) || DumbService.isDumbAware(provider)) {
        try {
          boolean goOn = processor.process(provider);
          if (!goOn) break;
        }
        catch (ProcessCanceledException e) {
          throw e;
        }
        catch (Exception e) {
          Logger.getInstance(PsiAugmentProvider.class).error("provider: " + provider, e);
        }
      }
    }
  }

  //</editor-fold>
}