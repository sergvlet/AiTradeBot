// src/main/java/com/chicu/aitradebot/web/advanced/renderers/AbstractEntityAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced.renderers;

import com.chicu.aitradebot.web.advanced.AdvancedRenderContext;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRenderer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.Version;
import jakarta.transaction.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

public abstract class AbstractEntityAdvancedRenderer<T> implements StrategyAdvancedRenderer {

    @PersistenceContext
    protected EntityManager em;

    private final Class<T> entityClass;

    protected AbstractEntityAdvancedRenderer(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    @Override
    @Transactional
    public String render(AdvancedRenderContext ctx) {
        long chatId = ctx.getChatId() == null ? 0L : ctx.getChatId();
        T entity = loadOrCreate(chatId);

        boolean editable = ctx.canSubmit();

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='row g-3'>");

        for (Field f : editableFields(entityClass)) {
            f.setAccessible(true);

            String name = f.getName();
            Class<?> type = f.getType();

            Object valueObj = getField(entity, f);
            String valueStr = valueObj == null ? "" : String.valueOf(valueObj);

            sb.append("<div class='col-md-6'>");
            sb.append("<label class='form-label'>").append(escapeHtml(humanize(name))).append("</label>");

            if (type == boolean.class || type == Boolean.class) {
                sb.append("<input type='hidden' name='").append(escapeHtml(name)).append("' value='false'>");
                sb.append("<div class='form-check'>");
                sb.append("<input class='form-check-input' type='checkbox' name='").append(escapeHtml(name)).append("' value='true' ");
                if (Boolean.TRUE.equals(valueObj)) sb.append("checked ");
                if (!editable) sb.append("disabled ");
                sb.append(">");
                sb.append("<label class='form-check-label'>Включено</label>");
                sb.append("</div>");
            } else if (type.isEnum()) {
                sb.append("<select class='form-select' name='").append(escapeHtml(name)).append("' ");
                if (!editable) sb.append("disabled ");
                sb.append(">");
                sb.append("<option value=''>—</option>");

                Object[] constants = type.getEnumConstants();
                String cur = valueObj == null ? null : String.valueOf(valueObj);
                if (constants != null) {
                    for (Object c : constants) {
                        String v = String.valueOf(c);
                        sb.append("<option value='").append(escapeHtml(v)).append("' ");
                        if (cur != null && cur.equals(v)) sb.append("selected ");
                        sb.append(">").append(escapeHtml(v)).append("</option>");
                    }
                }
                sb.append("</select>");
            } else {
                String inputType = pickInputType(type);
                sb.append("<input class='form-control' ")
                        .append("type='").append(inputType).append("' ")
                        .append("name='").append(escapeHtml(name)).append("' ")
                        .append("value='").append(escapeHtml(valueStr)).append("' ");
                if (isDecimal(type)) sb.append("step='any' inputmode='decimal' ");
                if (!editable) sb.append("readonly ");
                sb.append(">");
            }

            sb.append("</div>");
        }

        sb.append("</div>");

        if (!editable) {
            sb.append("<div class='alert alert-info mt-3 mb-0'>Режим AI: ручное редактирование отключено.</div>");
        }

        return sb.toString();
    }

    @Override
    @Transactional
    public void handleSubmit(AdvancedRenderContext ctx) {
        long chatId = ctx.getChatId() == null ? 0L : ctx.getChatId();
        T entity = loadOrCreate(chatId);

        // ✅ используем safeParams() из ctx (или fallback)
        Map<String, String> params = (ctx.getParams() != null) ? ctx.getParams() : Collections.emptyMap();

        for (Field f : editableFields(entityClass)) {
            f.setAccessible(true);
            String name = f.getName();

            // checkbox всегда приходит (через hidden=false + checkbox=true)
            if (!params.containsKey(name)) continue;

            String raw = params.get(name);
            setParsed(entity, f, raw);
        }

        em.merge(entity);
        em.flush();
    }


    protected T loadOrCreate(long chatId) {
        // select last by id
        String jpql = "select e from " + entityClass.getSimpleName() + " e where e.chatId = :chatId order by e.id desc";
        TypedQuery<T> q = em.createQuery(jpql, entityClass);
        q.setParameter("chatId", chatId);
        q.setMaxResults(1);

        List<T> list = q.getResultList();
        if (list != null && !list.isEmpty()) return list.get(0);

        try {
            T created = entityClass.getDeclaredConstructor().newInstance();
            Field chatIdField = findField(entityClass, "chatId");
            if (chatIdField == null) throw new IllegalStateException("No chatId field in " + entityClass.getName());
            chatIdField.setAccessible(true);
            chatIdField.set(created, chatId);

            em.persist(created);
            em.flush();
            return created;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create " + entityClass.getName(), e);
        }
    }

    // =====================================================
    // helpers
    // =====================================================

    private static List<Field> editableFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f == null) continue;
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (Modifier.isTransient(f.getModifiers())) continue;

                String n = f.getName();
                if (n == null) continue;

                if (n.equals("id") || n.equals("chatId") || n.equals("version")) continue;
                if (n.equals("createdAt") || n.equals("updatedAt") || n.equals("startedAt") || n.equals("stoppedAt")) continue;

                if (f.getAnnotation(jakarta.persistence.Id.class) != null) continue;
                if (f.getAnnotation(Version.class) != null) continue;

                out.add(f);
            }
        }
        out.sort(Comparator.comparing(Field::getName));
        return out;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Object getField(Object obj, Field f) {
        try {
            return f.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private static void setParsed(Object obj, Field f, String raw) {
        Class<?> t = f.getType();
        try {
            if (t == String.class) {
                f.set(obj, emptyToNull(raw));
                return;
            }
            if (t == Integer.class || t == int.class) {
                Integer v = parseInt(raw);
                if (t == int.class && v == null) return;
                f.set(obj, v);
                return;
            }
            if (t == Long.class || t == long.class) {
                Long v = parseLong(raw);
                if (t == long.class && v == null) return;
                f.set(obj, v);
                return;
            }
            if (t == Double.class || t == double.class) {
                Double v = parseDouble(raw);
                if (t == double.class && v == null) return;
                f.set(obj, v);
                return;
            }
            if (t == BigDecimal.class) {
                f.set(obj, parseBigDecimal(raw));
                return;
            }
            if (t == Boolean.class || t == boolean.class) {
                Boolean v = parseBool(raw);
                if (t == boolean.class) f.set(obj, Boolean.TRUE.equals(v));
                else f.set(obj, v);
                return;
            }
            if (t.isEnum()) {
                String s = emptyToNull(raw);
                if (s == null) {
                    f.set(obj, null);
                } else {
                    @SuppressWarnings({"unchecked","rawtypes"})
                    Object ev = Enum.valueOf((Class<? extends Enum>) t, s);
                    f.set(obj, ev);
                }
                return;
            }

            // даты руками обычно не правим, но если вдруг:
            if (t == Instant.class) {
                f.set(obj, parseInstant(raw));
                return;
            }
            if (t == LocalDateTime.class) {
                f.set(obj, parseLocalDateTime(raw));
                return;
            }
            if (t == OffsetDateTime.class) {
                f.set(obj, parseOffsetDateTime(raw));
                return;
            }

            // неизвестный тип — пропускаем
        } catch (Exception ignored) {
        }
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static Integer parseInt(String s) {
        String v = emptyToNull(s);
        if (v == null) return null;
        return Integer.valueOf(v);
    }

    private static Long parseLong(String s) {
        String v = emptyToNull(s);
        if (v == null) return null;
        return Long.valueOf(v);
    }

    private static Double parseDouble(String s) {
        String v = emptyToNull(s);
        if (v == null) return null;
        return Double.valueOf(v.replace(',', '.'));
    }

    private static BigDecimal parseBigDecimal(String s) {
        String v = emptyToNull(s);
        if (v == null) return null;
        return new BigDecimal(v.replace(',', '.'));
    }

    private static Boolean parseBool(String s) {
        String v = emptyToNull(s);
        if (v == null) return null;
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v.equals("1") || v.equalsIgnoreCase("yes");
    }

    private static Instant parseInstant(String s) {
        String v = emptyToNull(s);
        if (v == null) return null;
        return Instant.parse(v);
    }

    private static LocalDateTime parseLocalDateTime(String s) {
        String v = emptyToNull(s);
        if (v == null) return null;
        return LocalDateTime.parse(v);
    }

    private static OffsetDateTime parseOffsetDateTime(String s) {
        String v = emptyToNull(s);
        if (v == null) return null;
        return OffsetDateTime.parse(v);
    }

    private static boolean isDecimal(Class<?> t) {
        return t == Double.class || t == double.class || t == BigDecimal.class;
    }

    private static String pickInputType(Class<?> t) {
        if (t == Integer.class || t == int.class || t == Long.class || t == long.class) return "number";
        if (t == Double.class || t == double.class || t == BigDecimal.class) return "number";
        return "text";
    }

    private static String humanize(String s) {
        // windowSize -> Window Size
        StringBuilder out = new StringBuilder();
        char[] a = s.toCharArray();
        for (int i = 0; i < a.length; i++) {
            char c = a[i];
            if (i == 0) out.append(Character.toUpperCase(c));
            else if (Character.isUpperCase(c)) out.append(' ').append(c);
            else out.append(c);
        }
        return out.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
