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
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Keeps track of every metadata of every module and declaration.
 */
public class Metadata {

    private static final HashMap<String, ModuleMetas> attrs = new HashMap<>();
    private static final ArrayList<TestData> tests = new ArrayList<>();

    public static void registerMeta(String moduleName, String decl, String attr, Object value) {
        var mod = attrs.get(moduleName);
        if (mod == null) mod = new ModuleMetas();
        var map = mod.metas.get(decl);
        if (map == null) map = new HashMap<>();
        map.put(attr, value);
        mod.metas.put(decl, map);
        attrs.put(moduleName, mod);
    }

    public static void registerMetas(String modulename, String decl, Record rec) {
        rec.keys().stream().forEach(key -> {
            registerMeta(modulename, decl, key, rec.unsafeGet(key));
            if (key.equals("test")) registerTest(modulename, decl, rec.unsafeGet(key));
        });
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

    public static Map<String, Map<String, Object>> findMetadata(String moduleName) {
        var res = new Map<String, Map<String, Object>>().linear();
        var mod = attrs.get(moduleName);
        if (mod != null) {
            if (mod.moduleMeta != null && !mod.moduleMeta.isEmpty()) res.put(moduleName, Map.from(mod.moduleMeta));
            mod.metas.forEach((dec, map) -> {
                if (!map.isEmpty()) res.put(dec, Map.from(map));
            });
        }
        return res.forked();
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

    public static List<List<Object>> findMetadataAndDeclarationsFor(String tag) {
        var res = new List<List<Object>>().linear();
        attrs.forEach((mod, attr) -> {
            try {
                var clazz = Class.forName(mod + ".$Module");
                attr.metas.forEach((dec, ats) -> ats.forEach((tagName, obj) -> {
                    if (tag.equals(tagName)) {
                        try {
                            var f = clazz.getDeclaredField(dec);
                            var meta = List.of(f, obj);
                            res.addLast(meta);
                        } catch (NoSuchFieldException ignored) {
                        }
                    }
                }));
            } catch (ClassNotFoundException ignored) {
            }
        });
        return res.forked();
    }

    public static List<List<Object>> findTests() {
        var res = new List<List<Object>>().linear();
        for (var test : tests) {
            String desc = test.desc instanceof String ? (String) test.desc : "";
            res.addLast(List.of(test.module, test.decl, desc, test.field));
        }
        return res.forked();
    }

    private static void registerTest(String moduleName, String decl, Object desc) {
        try {
            var clazz = Class.forName(moduleName + ".$Module");
            var field = clazz.getDeclaredField(decl);
            tests.add(new TestData(moduleName, decl, desc, field));
        } catch (ClassNotFoundException | NoSuchFieldException ignored) {
        }
    }

    record TestData(String module, String decl, Object desc, Field field) {}

    static class ModuleMetas {
        // metadata of the module itself
        public HashMap<String, Object> moduleMeta = new HashMap<>();

        // metadata of declarations of this module
        public HashMap<String, HashMap<String, Object>> metas = new HashMap<>();
    }
}
