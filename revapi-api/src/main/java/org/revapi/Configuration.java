/*
 * Copyright 2014 Lukas Krejci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package org.revapi;

import java.util.Locale;
import java.util.Map;

/**
 * @author Lukas Krejci
 * @since 0.1
 */
public final class Configuration {
    private final Locale locale;
    private final Map<String, String> properties;

    public Configuration(Locale locale, Map<String, String> properties) {
        this.locale = locale;
        this.properties = properties;
    }

    public Locale getLocale() {
        return locale;
    }

    public Map<String, String> getProperties() {
        return properties;
    }
}
