/**
 * Copyright 2022 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah;

import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Map;
import io.lacuna.bifurcan.Set;
import novah.collections.Record;

import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * Keeps track of every metadata of every module and declaration.
 */
public class Metadata {

    private static final HashMap<String, ModuleMetas> attrs = new HashMap<>();

    public static void registerMeta(String moduleName, String decl, String attr, Object value) {
        var mod = attrs.get(moduleName);
        if (mod == null) mod = new ModuleMetas();
        var map = mod.metas.get(decl);
        if (map == null) map = new HashMap<>();
        map.put(attr, value);
        mod.metas.put(decl, map);
        attrs.put(moduleName, mod);
    }

    public static void registerMeta(String moduleName, String attr, Object value) {
        var mod = attrs.get(moduleName);
        if (mod == null) mod = new ModuleMetas();
        mod.moduleMeta.put(attr, value);
        attrs.put(moduleName, mod);
    }

    public static void registerMetas(String modulename, String decl, Record rec) {
        rec.keys().stream().forEach(key -> registerMeta(modulename, decl, key, rec.unsafeGet(key)));
    }

    public static Map<String, Object> allMetadataOf(String moduleName, String decl) {
        var mod = attrs.get(moduleName);
        if (mod == null) return Map.empty();
        var map = mod.metas.get(decl);
        if (map == null) return Map.empty();
        return Map.from(map);
    }

    public static Map<String, Object> allMetadataOf(String moduleName) {
        var mod = attrs.get(moduleName);
        if (mod == null) return Map.empty();
        return Map.from(mod.moduleMeta);
    }

    public static List<Map<String, Object>> findMetadata(String moduleName) {
        var list = new List<Map<String, Object>>().linear();
        var mod = attrs.get(moduleName);
        if (mod != null) {
            if (mod.moduleMeta != null) list.addLast(Map.from(mod.moduleMeta));
            mod.metas.forEach((dec, map) -> list.addLast(Map.from(map)));
        }
        return list.forked();
    }

    public static Set<String> modulesForTag(String tag) {
        Set<String> set = new Set<String>().linear();
        MODFOR: for (var mod: attrs.keySet()) {
            var attr = attrs.get(mod);
            for (var atts : attr.metas.entrySet()) {
                for (var tagName : atts.getValue().keySet()) {
                    if (tag.equals(tagName)) {
                        set.add(mod);
                        continue MODFOR;
                    }
                }
            }
        }
        return set.forked();
    }

    public static List<Field> declarationsForTag(String moduleName, String tag) {
        var fields = new List<Field>().linear();
        var attr = attrs.get(moduleName);
        if (attr == null) return fields.forked();
        try {
            var clazz = Class.forName(attr + ".$Module");
            attr.metas.forEach((dec, ats) -> ats.forEach((tagName, obj) -> {
                if (tag.equals(tagName)) {
                    try {
                        var f = clazz.getDeclaredField(dec);
                        fields.addLast(f);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }));
        } catch (ClassNotFoundException ignored) {
        }
        return fields.forked();
    }

    public static List<Field> declarationsForTag(String tag) {
        var fields = new List<Field>().linear();
        attrs.forEach((mod, attr) -> {
            try {
                var clazz = Class.forName(mod + ".$Module");
                attr.metas.forEach((dec, ats) -> ats.forEach((tagName, obj) -> {
                    if (tag.equals(tagName)) {
                        try {
                            var f = clazz.getDeclaredField(dec);
                            fields.addLast(f);
                        } catch (NoSuchFieldException ignored) {
                        }
                    }
                }));
            } catch (ClassNotFoundException ignored) {
            }
        });
        return fields.forked();
    }

    static class ModuleMetas {
        // metadata of the module itself
        public HashMap<String, Object> moduleMeta = new HashMap<>();

        // metadata of declarations of this module
        public HashMap<String, HashMap<String, Object>> metas = new HashMap<>();
    }
}
