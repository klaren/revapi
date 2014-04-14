/*
 * Copyright 2014 Lukas Krejci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package org.revapi.java.checks.methods;

import javax.annotation.Nullable;
import javax.lang.model.element.ExecutableElement;

import org.revapi.java.checks.common.VisibilityChanged;
import org.revapi.java.spi.Code;

/**
 * @author Lukas Krejci
 * @since 0.1
 */
public final class VisibilityIncreased extends VisibilityChanged {
    public VisibilityIncreased() {
        super(Code.METHOD_VISIBILITY_INCREASED, true);
    }

    @Override
    protected void doVisitMethod(@Nullable ExecutableElement oldMethod, @Nullable ExecutableElement newMethod) {
        doVisit(oldMethod, newMethod);
    }
}
