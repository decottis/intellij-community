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
package com.intellij.openapi.roots;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;

/**
 * Implement this EP to provide additional library roots. It can be useful when it's undesirable to create
 * {@link com.intellij.openapi.roots.libraries.Library} and attach it to a module via {@link OrderEntry}.
 * Additional library roots will extend {@link com.intellij.psi.search.GlobalSearchScope#allScope(Project)}
 * (in UI, "Project and Libraries" scope). Also, files contained in the roots will be shown as library files
 * in Project View and will be available in "Navigate | File..." popup.
 *
 * Please note it is an experimental API that is subject to incompatible changes, or even removal, in a future release.
 */
@ApiStatus.Experimental
public abstract class AdditionalLibraryRootsProvider {
  public static final ExtensionPointName<AdditionalLibraryRootsProvider> EP_NAME = ExtensionPointName.create("com.intellij.additionalLibraryRootsProvider");

  /**
   * Returns a collection of {@link SyntheticLibrary}.
   * This method is suitable when it's easier to collect all additional library roots associated with {@code Project},
   * instead of {@code Module}. E.g. JavaScript libraries can be associated with files or folders allowing more
   * fine-grained control.
   *
   * @param project  Project instance
   * @return a collection of {@link SyntheticLibrary}
   */
  @NotNull
  public Collection<SyntheticLibrary> getAdditionalProjectLibraries(@NotNull Project project) {
    //noinspection deprecation
    Collection<VirtualFile> roots = getAdditionalProjectLibrarySourceRoots(project);
    if (roots.isEmpty()) {
      return Collections.emptyList();
    }
    return Collections.singletonList(SyntheticLibrary.newFixedLibrary(null, roots));
  }

  @SuppressWarnings("DeprecatedIsStillUsed")
  @NotNull
  public Collection<VirtualFile> getAdditionalProjectLibrarySourceRoots(@NotNull Project project) {
    return Collections.emptyList();
  }
}
