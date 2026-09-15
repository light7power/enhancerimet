package me.firesun.dingtalk.enhancement.modern;

import android.util.Log;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Color;
import android.content.ContentValues;
import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;

/**
 * Modern libxposed implementation for Rimet 8.5.5 (versionCode 1274).
 * Targets only the two user-facing features from the legacy module.
 */
public final class DingTalkModule extends XposedModule {
    private static final String TAG = "DingTalkEnhancement";
    private static final String TARGET = "com.alibaba.android.rimet";
    private static final String MESSAGE = "com.alibaba.wukong.im.message.MessageImpl";
    private static final String CALLBACK = "com.alibaba.wukong.Callback";
    private static final String CONTENT_VALUES = "android.content.ContentValues";
    private static final String PREFS_GROUP = "config";
    private static final String KEY_ANTI_RECALL = "anti_recall";
    private static final String KEY_MASK_READ = "mask_read";
    private static final Map<Long, String> pendingRecallNotices = new ConcurrentHashMap<>();
    private static final Map<Long, Object> recallSnapshots = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<Long>> ownRecallIds = new ThreadLocal<>();
    private static final ThreadLocal<Integer> incomingRecallDepth = new ThreadLocal<>();
    private static final ThreadLocal<Integer> localRecallDepth = new ThreadLocal<>();
    private static final ThreadLocal<Integer> resolvedRecallCount = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> containsForeignRecall = new ThreadLocal<>();
    private SharedPreferences remotePrefs;
    private boolean installed;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded: " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (TARGET.equals(param.getPackageName())) install(param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        install(param.getClassLoader());
    }

    private synchronized void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        try {
            remotePrefs = getRemotePreferences(PREFS_GROUP);
            Class<?> callback = Class.forName(CALLBACK, false, cl);
            // DexSQL signatures: Lnii;.I(Map,List,List,Callback) and K(String,long,Callback).
            hook(cl, "nii", "I", new Class<?>[]{Map.class, java.util.List.class, java.util.List.class, callback},
                    "mask-read-batch");
            hook(cl, "nii", "K", new Class<?>[]{String.class, long.class, callback},
                    "mask-read-single");

            // DexSQL-confirmed incoming recall entry:
            // MessageNoticeHandler -> MessageCache.F(cid, mid) -> MessageCache.M0(cid, msg, recallStatus).
            Class<?> messageImpl = Class.forName(MESSAGE, false, cl);
            Class<?> messageCache = Class.forName(
                    "com.alibaba.wukong.im.message.MessageCache", false, cl);
            Method recallUpdate = messageCache.getDeclaredMethod(
                    "M0", String.class, messageImpl, Integer.class);
            hook(recallUpdate)
                    .setId("capture-recall-before-native-update")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        final Object[] snapshotHolder = new Object[1];
                        Object message = chain.getArg(1);
                        Object status = chain.getArg(2);
                        if (enabled(KEY_ANTI_RECALL, true) && isIncomingRecallContext()
                                && !isLocalRecallContext()
                                && message != null && Integer.valueOf(1).equals(status)
                                && messageIdentity(cl, message) < 0) {
                            // Read the display name from the live message before cloning it.
                            String notice = senderLabel(message)
                                    + "尝试撤回上一条消息 [已阻止]";
                            Object snapshot = cloneMessage(cl, message);
                            long mid = longValue(snapshot, "mid", "mMid");
                            pendingRecallNotices.put(mid, notice);
                            pendingRecallNotices.put(longValue(snapshot, "messageId", "mMid"), notice);
                            snapshotHolder[0] = snapshot;
                        }
                        Object result = chain.proceed();
                        Object snapshot = snapshotHolder[0];
                        if (snapshot != null) {
                            restoreOriginalMessage(cl, (String) chain.getArg(0), snapshot);
                        }
                        return result;
                    });

            // DexSQL-confirmed bulk recall path. Let it notify the UI first; the
            // f0 hook below restores the copied message after that notification.
            Class<?> store = findObfuscatedClass(cl, "ofi");
            Class<?> contentValues = Class.forName(CONTENT_VALUES, false, cl);
            installIncomingRecallContextHooks(cl);
            installLocalRecallContextHooks(cl, messageCache);
            installBulkRecallSnapshotHook(cl, store);
            installRecallCapabilityHook(cl, messageImpl);
            installBulkRecallRestoreHook(cl, messageCache, messageImpl);
            installRecallRowHook(cl);
            installLegacyRecallRowHook(cl);
            installLegacyAdapterStripHook(cl);
            installUserMessageStripHook(cl);
            log(Log.INFO, TAG, "hooks installed for Rimet 8.5.5");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook installation failed", t);
        }
    }

    private void installBulkRecallSnapshotHook(ClassLoader cl, Class<?> store) throws Exception {
        Class<?> conversation = Class.forName(
                "com.alibaba.wukong.im.conversation.ConversationImpl", false, cl);
        Method resolve = store.getDeclaredMethod("C", conversation, List.class);
        hook(resolve)
                .setId("capture-bulk-recall-original")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (enabled(KEY_ANTI_RECALL, true) && isIncomingRecallContext()
                            && !isLocalRecallContext() && result instanceof Collection<?>) {
                        for (Object message : (Collection<?>) result) {
                            if (message == null || intValue(message, "recallStatus", "mRecallStatus") != 0) {
                                continue;
                            }
                            if (messageIdentity(cl, message) >= 0) continue;
                            Object snapshot = cloneMessage(cl, message);
                            long mid = longValue(snapshot, "mid", "mMid");
                            recallSnapshots.put(mid, snapshot);
                            pendingRecallNotices.put(mid,
                                    senderLabel(message) + "尝试撤回上一条消息 [已阻止]");
                        }
                    }
                    return result;
                });
    }

    private void installBulkRecallRestoreHook(ClassLoader cl, Class<?> messageCache,
                                              Class<?> messageImpl) throws Exception {
        Method recallBatch = messageCache.getDeclaredMethod("f0", String.class, List.class);
        Method restore = messageCache.getDeclaredMethod("M0", String.class,
                messageImpl, Integer.class);
        hook(recallBatch)
                .setId("restore-after-bulk-recall-notification")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Set<Long> ownIds = new HashSet<>();
                    int resolvedCount = 0;
                    boolean foreign = false;
                    Object cidArg = chain.getArg(0);
                    Object idsArg = chain.getArg(1);
                    if (cidArg instanceof String && idsArg instanceof Iterable<?>) {
                        // K() falls back to the database when the conversation is
                        // not cached; I() only inspects the in-memory list.
                        Method find = messageCache.getDeclaredMethod("K", String.class, long.class);
                        find.setAccessible(true);
                        for (Object id : (Iterable<?>) idsArg) {
                            if (id instanceof Number) {
                                Object message = find.invoke(chain.getThisObject(), cidArg,
                                        ((Number) id).longValue());
                                if (message != null) {
                                    int identity = messageIdentity(cl, message);
                                    if (identity != 0) resolvedCount++;
                                    if (identity > 0) {
                                        ownIds.add(((Number) id).longValue());
                                    } else if (identity < 0) {
                                        foreign = true;
                                    }
                                }
                            }
                        }
                    }
                    ownRecallIds.set(ownIds);
                    resolvedRecallCount.set(resolvedCount);
                    containsForeignRecall.set(foreign);
                    Object result;
                    try {
                        result = chain.proceed();
                    } finally {
                        ownRecallIds.remove();
                        resolvedRecallCount.remove();
                        containsForeignRecall.remove();
                    }
                    if (isLocalRecallContext()
                            || !enabled(KEY_ANTI_RECALL, true) || !(cidArg instanceof String)) return result;
                    for (Object id : (List<?>) chain.getArg(1)) {
                        if (!(id instanceof Number)) continue;
                        Object snapshot = recallSnapshots.remove(((Number) id).longValue());
                        if (snapshot == null) continue;
                        restore.invoke(chain.getThisObject(), cidArg, snapshot, Integer.valueOf(0));
                    }
                    return result;
                });
    }

    /**
     * When the conversation is not open, MessageCache.f0() has no in-memory
     * conversation and writes recall=1 directly through ofi.f(). In that path
     * ofi.C() is never called, so there is no snapshot to restore afterwards.
     * Block only that no-snapshot database write; the foreground path keeps
     * going through f0 so DingTalk can still render its native recall row.
     */
    private void installRecallDatabaseGuardHook(ClassLoader cl, Class<?> store,
                                                Class<?> contentValues) throws Exception {
        Method update = store.getDeclaredMethod("f", String.class, List.class, contentValues);
        hook(update)
                .setId("protect-background-recall-database-write")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (!enabled(KEY_ANTI_RECALL, true)) return chain.proceed();
                    Object values = chain.getArg(2);
                    if (!isRecallUpdate(values)) return chain.proceed();
                    // A direct ofi.f() call is also used by the local user's
                    // own recall operation. Only guard f() while it is nested
                    // inside the confirmed MessageCache.f0() batch path.
                    if (!isIncomingRecallContext()) return chain.proceed();
                    // Never block when the sender identity is unavailable.
                    // This is important for the local user's "recalling..."
                    // RPC callback, which can briefly lack a message object.
                    Integer resolved = resolvedRecallCount.get();
                    if (resolved == null || resolved == 0
                            || !Boolean.TRUE.equals(containsForeignRecall.get())) {
                        return chain.proceed();
                    }
                    if (containsOwnRecallId(chain.getArg(1))) return chain.proceed();
                    if (hasSnapshotForIds(chain.getArg(1))) return chain.proceed();
                    Log.i(TAG, "blocked background recall database update");
                    return Integer.valueOf(0);
                });
    }

    /**
     * The original DingTalk anti-recall module keeps the local recall action
     * available by forcing MessageImpl.canRecall() to true. Do not intercept
     * ofi.f() here: returning 0 from that database method leaves DingTalk's
     * own recall request stuck at “recalling…”.
     */
    private void installRecallCapabilityHook(ClassLoader cl, Class<?> messageImpl)
            throws Exception {
        Method canRecall = messageImpl.getDeclaredMethod("canRecall");
        hook(canRecall)
                .setId("allow-local-recall-action")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> enabled(KEY_ANTI_RECALL, true)
                        ? Boolean.TRUE : chain.proceed());
    }

    /**
     * DexSQL-confirmed incoming recall entry points. Local user recalls enter
     * through MessageImpl$a0/MessageImpl$z and therefore never acquire this
     * context.
     */
    private void installIncomingRecallContextHooks(ClassLoader cl) throws Exception {
        Class<?> ack = Class.forName(
                "com.laiwang.idl.client.push.ReceiverMessageHandler$AckCallback", false, cl);
        Class<?> notice = Class.forName(
                "com.alibaba.wukong.idl.im.models.MessageNoticeModel", false, cl);
        Method noticeHandler = Class.forName("Ligi", false, cl).getDeclaredMethod(
                "a", ack, notice);
        hookIncomingContext(noticeHandler, "incoming-recall-notice-context");

        Class<?> syncAck = Class.forName("com.alibaba.wukong.sync.SyncAck", false, cl);
        Method syncHandler = Class.forName("Liji", false, cl).getDeclaredMethod(
                "onReceived", List.class, syncAck);
        hookIncomingContext(syncHandler, "incoming-recall-sync-context");
    }

    private void hookIncomingContext(Method method, String id) {
        hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Integer depth = incomingRecallDepth.get();
                    incomingRecallDepth.set(depth == null ? 1 : depth + 1);
                    try {
                        return chain.proceed();
                    } finally {
                        if (depth == null) incomingRecallDepth.remove();
                        else incomingRecallDepth.set(depth);
                    }
        });
    }

    /**
     * DexSQL-confirmed local recall paths. MessageImpl$a0 and MessageImpl$z
     * call M0(), while MessageCache.g0()/h0() call f0(). Mark these stacks
     * so anti-recall restoration cannot touch the user's own request.
     */
    private void installLocalRecallContextHooks(ClassLoader cl, Class<?> messageCache)
            throws Exception {
        Class<?> messageImpl = Class.forName(MESSAGE, false, cl);
        Class<?> callback = Class.forName(CALLBACK, false, cl);
        hookLocalContext(Class.forName(MESSAGE + "$a0", false, cl)
                        .getDeclaredMethod("onExecuteRpc", Void.class, callback),
                "local-recall-rpc-a0");
        Class<?> epc = Class.forName("epe$c", false, cl);
        hookLocalContext(Class.forName(MESSAGE + "$z", false, cl)
                        .getDeclaredMethod("onAfterRpc", epc),
                "local-recall-rpc-z");
        hookLocalContext(messageCache.getDeclaredMethod("g0", String.class, messageImpl),
                "local-recall-cache-g0");
        hookLocalContext(messageCache.getDeclaredMethod("h0", String.class, List.class),
                "local-recall-cache-h0");
    }

    private void hookLocalContext(Method method, String id) {
        hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Integer depth = localRecallDepth.get();
                    localRecallDepth.set(depth == null ? 1 : depth + 1);
                    try {
                        return chain.proceed();
                    } finally {
                        if (depth == null) localRecallDepth.remove();
                        else localRecallDepth.set(depth);
                    }
                });
    }

    private static boolean isIncomingRecallContext() {
        Integer depth = incomingRecallDepth.get();
        return depth != null && depth > 0;
    }

    private static boolean isLocalRecallContext() {
        Integer depth = localRecallDepth.get();
        return depth != null && depth > 0;
    }

    private static boolean isRecallUpdate(Object values) {
        if (!(values instanceof ContentValues)) return false;
        Integer recall = ((ContentValues) values).getAsInteger("recall");
        return Integer.valueOf(1).equals(recall);
    }

    private static boolean hasSnapshotForIds(Object ids) {
        if (!(ids instanceof Iterable<?>)) return false;
        for (Object id : (Iterable<?>) ids) {
            if (id instanceof Number && recallSnapshots.containsKey(((Number) id).longValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOwnRecallId(Object ids) {
        Set<Long> ownIds = ownRecallIds.get();
        if (ownIds == null || ownIds.isEmpty() || !(ids instanceof Iterable<?>)) return false;
        for (Object id : (Iterable<?>) ids) {
            if (id instanceof Number && ownIds.contains(((Number) id).longValue())) return true;
        }
        return false;
    }

    private void installRecallRowHook(ClassLoader cl) throws Exception {
        Class<?> holder = Class.forName(
                "com.alibaba.android.dingtalkim.chatv2.plugin.list.view.viewholder.RecallRVViewHolder",
                false, cl);
        Class<?> viewModel = Class.forName("ncj", false, cl);
        Method bind = holder.getDeclaredMethod("K0", viewModel);
        hook(bind)
                .setId("native-recall-system-strip")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object model = chain.getArg(0);
                        Object recalled = model.getClass().getMethod("h").invoke(model);
                        long mid = longValue(recalled, "mid", "mMid");
                        String notice = pendingRecallNotices.get(mid);
                        if (notice != null) {
                            TextView text = (TextView) findField(chain.getThisObject(), "T0");
                            if (text != null) text.setText(notice);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "native recall strip update skipped", t);
                    }
                    return result;
                });
    }

    private void installLegacyRecallRowHook(ClassLoader cl) throws Exception {
        Class<?> holder = Class.forName(
                "com.alibaba.android.dingtalkim.adapters.RecallViewHolder", false, cl);
        Class<?> message = Class.forName("com.alibaba.wukong.im.Message", false, cl);
        Method bind = holder.getDeclaredMethod("x", android.app.Activity.class,
                long.class, message, int.class);
        hook(bind)
                .setId("legacy-native-recall-system-strip")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object msg = chain.getArg(3);
                        String notice = findPendingNotice(msg);
                        TextView text = (TextView) findField(chain.getThisObject(), "u1");
                        if (text != null && notice != null) text.setText(notice);
                    } catch (Throwable t) {
                        Log.w(TAG, "legacy native recall strip update skipped", t);
                    }
                    return result;
                });
    }

    private void installLegacyAdapterStripHook(ClassLoader cl) throws Exception {
        Class<?> adapter = Class.forName(
                "com.alibaba.android.dingtalkim.adapters.ChatItemAdapter", false, cl);
        Class<?> message = Class.forName("com.alibaba.wukong.im.Message", false, cl);
        Method getView = adapter.getDeclaredMethod("getView", int.class,
                View.class, ViewGroup.class);
        Method getMessage = adapter.getDeclaredMethod("getMessage", int.class);
        hook(getView)
                .setId("legacy-chat-item-recall-system-strip")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!(result instanceof View)) return result;
                        Object msg = getMessage.invoke(chain.getThisObject(), chain.getArg(0));
                        String notice = findPendingNotice(msg);
                        addOrUpdateRecallStrip((View) result, null, notice);
                    } catch (Throwable t) {
                        Log.w(TAG, "legacy adapter recall strip update skipped", t);
                    }
                    return result;
                });
    }

    private void installUserMessageStripHook(ClassLoader cl) throws Exception {
        Class<?> holder = Class.forName(
                "com.alibaba.android.dingtalkim.chatv2.plugin.list.view.viewholder.AbsUserMsgRVViewHolder",
                false, cl);
        Class<?> viewModel = Class.forName("ncj", false, cl);
        Method bind = holder.getDeclaredMethod("K0", viewModel);
        hook(bind)
                .setId("in-chat-recall-system-strip")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object model = chain.getArg(0);
                        Object message = model.getClass().getMethod("h").invoke(model);
                        String notice = findPendingNotice(message);
                        Object bubble = invokeNoArg(chain.getThisObject(), "M");
                        Object item = findField(chain.getThisObject(), "itemView");
                        if (item instanceof View) {
                            addOrUpdateRecallStrip((View) item,
                                    bubble instanceof View ? (View) bubble : null, notice);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "in-chat recall strip update skipped", t);
                    }
                    return result;
                });
    }

    private void hook(ClassLoader cl, String shortClass, String methodName,
                      Class<?>[] parameterTypes, String id) throws Exception {
        Class<?> owner = findObfuscatedClass(cl, shortClass);
        Method method = owner.getDeclaredMethod(methodName, parameterTypes);
        hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (("mask-read-batch".equals(id) || "mask-read-single".equals(id))
                            && enabled(KEY_MASK_READ, true)) {
                        return null;
                    }
                    return chain.proceed();
                });
    }

    private static Object cloneMessage(ClassLoader cl, Object message) throws Exception {
        Class<?> impl = Class.forName(MESSAGE, false, cl);
        Object copy = impl.getDeclaredMethod("newInstance").invoke(null);
        Class<?> copier = Class.forName("com.alibaba.wukong.im.message.d", false, cl);
        copier.getDeclaredMethod("A", impl, impl, boolean.class)
                .invoke(null, message, copy, true);
        return copy;
    }

    private static void restoreOriginalMessage(ClassLoader cl, String conversationId,
                                               Object snapshot) {
        try {
            Class<?> proxy = findObfuscatedClass(cl, "qfi");
            Object instance = proxy.getDeclaredMethod("W").invoke(null);
            Class<?> store = findObfuscatedClass(cl, "ofi");
            Method update = store.getDeclaredMethod("T", String.class, String.class, List.class);
            update.setAccessible(true);
            update.invoke(instance, (String) Class.forName(
                    "com.alibaba.wukong.im.base.IMDatabase", false, cl)
                    .getDeclaredMethod("getWritableDatabase").invoke(null),
                    conversationId, Collections.singletonList(snapshot));
        } catch (Throwable t) {
            Log.w(TAG, "original message restore skipped", t);
        }
    }

    private static Class<?> findObfuscatedClass(ClassLoader cl, String shortName) throws ClassNotFoundException {
        // DexSQL identified Lnii;, Lofi;, and Lqfi; in this exact build.
        String descriptor = "L" + shortName + ";";
        return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'), false, cl);
    }

    private static String senderLabel(Object message) {
        try {
            Object value = invokeNoArg(message, "senderName");
            String name = value == null ? null : String.valueOf(value);
            return name == null || name.length() == 0 || "null".equals(name) ? "对方" : name;
        } catch (Throwable t) {
            return "对方";
        }
    }

    /** DexSQL-confirmed current-user path: UserEngineInterface.f().d(). */
    private static boolean isOwnMessage(ClassLoader cl, Object message) {
        return messageIdentity(cl, message) > 0;
    }

    /** Returns 1 for own, -1 for foreign, and 0 when identity is unavailable. */
    private static int messageIdentity(ClassLoader cl, Object message) {
        try {
            long senderId = ((Number) invokeNoArg(message, "senderId")).longValue();
            if (senderId <= 0) return 0;
            Class<?> users = Class.forName(
                    "com.alibaba.android.dingtalk.userbase.UserEngineInterface", false, cl);
            Method factory = users.getDeclaredMethod("f");
            factory.setAccessible(true);
            Object engine = factory.invoke(null);
            if (engine == null) return 0;
            Method current = users.getDeclaredMethod("d");
            current.setAccessible(true);
            long currentId = ((Number) current.invoke(engine)).longValue();
            if (currentId <= 0) return 0;
            return senderId == currentId ? 1 : -1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String findPendingNotice(Object message) {
        long mid;
        try {
            mid = longValue(message, "mid", "mMid");
            String notice = pendingRecallNotices.get(mid);
            if (notice != null) return notice;
            return pendingRecallNotices.get(longValue(message, "messageId", "mMid"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addOrUpdateRecallStrip(View itemView, View bubble, String notice) {
        ViewGroup parent = itemView instanceof ViewGroup ? (ViewGroup) itemView : null;
        if (parent == null && bubble != null && bubble.getParent() instanceof ViewGroup) {
            parent = (ViewGroup) bubble.getParent();
        }
        if (parent == null) return;
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if ("DingTalkEnhancement.RecallStrip".equals(child.getTag())) {
                parent.removeViewAt(i);
            }
        }
        if (notice == null) return;
        TextView strip = new TextView(parent.getContext());
        strip.setTag("DingTalkEnhancement.RecallStrip");
        strip.setText(notice);
        strip.setTextColor(Color.rgb(110, 110, 110));
        strip.setTextSize(13);
        strip.setGravity(android.view.Gravity.CENTER);
        strip.setPadding(0, 5, 0, 5);
        strip.setBackground(null);
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(strip, params);
    }

    private static Object findField(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static long longValue(Object target, String method, String field) throws Exception {
        try {
            return ((Number) invokeNoArg(target, method)).longValue();
        } catch (Throwable ignored) {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.getLong(target);
        }
    }

    private static int intValue(Object target, String method, String field) throws Exception {
        try {
            return ((Number) invokeNoArg(target, method)).intValue();
        } catch (Throwable ignored) {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(target);
        }
    }

    private boolean enabled(String key, boolean fallback) {
        try {
            if (remotePrefs == null) remotePrefs = getRemotePreferences(PREFS_GROUP);
            return remotePrefs.getBoolean(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
