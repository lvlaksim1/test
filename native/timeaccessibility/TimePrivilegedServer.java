// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Persistent uid=2000 service started by the Shizuku-style native starter.
 *
 * The server delivers its Binder to the app through an exported ContentProvider
 * and keeps running independently from the normal application process. When
 * the app process dies, the acknowledgement Binder dies too; the server then
 * re-delivers itself when the provider becomes available again.
 */
public final class TimePrivilegedServer extends Binder {
    private static final int SHELL_UID = 2000;
    private static final String CALLING_PACKAGE = "com.android.shell";
    private static final String DESCRIPTOR = "__PACKAGE__.timeaccessibility.ITimeMachinePrivileged";
    private static final String METHOD_SEND_BINDER = "sendBinder";
    private static final String EXTRA_BINDER = "time_machine_privileged_binder";
    private static final String EXTRA_ACK_BINDER = "time_machine_privileged_ack";

    private static final int TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_SET_TIME = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TRANSACTION_AUTO_TIME = IBinder.FIRST_CALL_TRANSACTION + 2;

    private final String packageName;
    private final String authority;
    private final int applicationUid;
    private final int userId;
    private final IBinder providerToken = new Binder();

    private volatile IBinder acknowledgementBinder;
    private final IBinder.DeathRecipient acknowledgementDeath = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            acknowledgementBinder = null;
        }
    };

    private TimePrivilegedServer(String packageName, String authority, int applicationUid) {
        this.packageName = packageName;
        this.authority = authority;
        this.applicationUid = applicationUid;
        this.userId = applicationUid / 100000;
    }

    public static void main(String[] args) {
        if (Process.myUid() != SHELL_UID || args == null || args.length < 3) return;

        String packageName = args[0];
        String authority = args[1];
        int appUid;
        try {
            appUid = Integer.parseInt(args[2]);
        } catch (Throwable ignored) {
            return;
        }
        if (packageName.length() == 0 || authority.length() == 0 || appUid <= 0) return;

        exemptHiddenApis();
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();

        final TimePrivilegedServer server = new TimePrivilegedServer(packageName, authority, appUid);
        Thread sender = new Thread(new Runnable() {
            @Override
            public void run() {
                server.deliveryLoop();
            }
        }, "time-machine-binder-sender");
        sender.setDaemon(false);
        sender.start();

        Looper.loop();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }

        if (code == TRANSACTION_PING || code == TRANSACTION_SET_TIME || code == TRANSACTION_AUTO_TIME) {
            try {
                data.enforceInterface(DESCRIPTOR);
                if (Binder.getCallingUid() != applicationUid) {
                    reply.writeException(new SecurityException("Caller uid is not the Time Machine application"));
                    return true;
                }

                Result result;
                if (code == TRANSACTION_PING) {
                    result = new Result(Process.myUid() == SHELL_UID, "uid=" + Process.myUid() + " pid=" + Process.myPid());
                } else if (code == TRANSACTION_SET_TIME) {
                    result = setTime(data.readLong());
                } else {
                    result = setAutomaticTime(data.readInt() != 0);
                }

                reply.writeNoException();
                reply.writeInt(result.success ? 1 : 0);
                reply.writeString(result.detail);
                return true;
            } catch (Throwable failure) {
                reply.writeException(new IllegalStateException(clean(String.valueOf(failure))));
                return true;
            }
        }

        return super.onTransact(code, data, reply, flags);
    }

    private void deliveryLoop() {
        while (true) {
            IBinder current = acknowledgementBinder;
            if (current == null || !current.isBinderAlive() || !current.pingBinder()) {
                acknowledgementBinder = null;
                IBinder next = sendBinderToApplication();
                if (next != null && next.isBinderAlive() && next.pingBinder()) {
                    try {
                        next.linkToDeath(acknowledgementDeath, 0);
                        acknowledgementBinder = next;
                    } catch (Throwable ignored) {
                        acknowledgementBinder = null;
                    }
                }
            }

            try {
                Thread.sleep(acknowledgementBinder == null ? 350L : 1200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private IBinder sendBinderToApplication() {
        Object activityManager = null;
        Object provider = null;
        try {
            activityManager = getActivityManager();
            if (activityManager == null) return null;

            provider = getContentProviderExternal(activityManager);
            if (provider == null) return null;

            Bundle extras = new Bundle();
            extras.putBinder(EXTRA_BINDER, this);
            Bundle response = callProvider(provider, extras);
            if (response == null) return null;
            return response.getBinder(EXTRA_ACK_BINDER);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (activityManager != null && provider != null) {
                removeContentProviderExternal(activityManager);
            }
        }
    }

    private Object getActivityManager() throws Exception {
        Class<?> activityManagerClass = Class.forName("android.app.ActivityManager");
        Method getService = activityManagerClass.getDeclaredMethod("getService");
        getService.setAccessible(true);
        return getService.invoke(null);
    }

    private Object getContentProviderExternal(Object activityManager) {
        for (Method method : activityManagerMethods(activityManager)) {
            if (!"getContentProviderExternal".equals(method.getName())) continue;
            try {
                method.setAccessible(true);
                Object[] arguments = buildGetProviderArguments(method.getParameterTypes());
                Object holder = method.invoke(activityManager, arguments);
                Object provider = unwrapProvider(holder);
                if (provider != null) return provider;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Method[] activityManagerMethods(Object activityManager) {
        try {
            return Class.forName("android.app.IActivityManager").getMethods();
        } catch (Throwable ignored) {
            return activityManager.getClass().getMethods();
        }
    }

    private Object[] buildGetProviderArguments(Class<?>[] types) {
        Object[] values = new Object[types.length];
        int stringIndex = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == String.class) {
                values[i] = stringIndex++ == 0 ? authority : "time_machine_server";
            } else if (type == int.class || type == Integer.TYPE) {
                values[i] = userId;
            } else if (IBinder.class.isAssignableFrom(type)) {
                values[i] = providerToken;
            } else if (type == boolean.class || type == Boolean.TYPE) {
                values[i] = false;
            } else {
                values[i] = null;
            }
        }
        return values;
    }

    private Object unwrapProvider(Object holder) {
        if (holder == null) return null;
        if (hasMethod(holder.getClass(), "asBinder")) return holder;
        try {
            Field providerField = holder.getClass().getField("provider");
            providerField.setAccessible(true);
            return providerField.get(holder);
        } catch (Throwable ignored) {
        }
        try {
            Field providerField = holder.getClass().getDeclaredField("provider");
            providerField.setAccessible(true);
            return providerField.get(holder);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Bundle callProvider(Object provider, Bundle extras) {
        Method[] methods;
        try {
            methods = Class.forName("android.content.IContentProvider").getMethods();
        } catch (Throwable ignored) {
            methods = provider.getClass().getMethods();
        }

        for (Method method : methods) {
            if (!"call".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 4 || !Bundle.class.isAssignableFrom(types[types.length - 1])) continue;
            try {
                method.setAccessible(true);
                Object[] arguments = buildProviderCallArguments(types, extras);
                Object result = method.invoke(provider, arguments);
                if (result instanceof Bundle) return (Bundle) result;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object[] buildProviderCallArguments(Class<?>[] types, Bundle extras) throws Exception {
        Object[] values = new Object[types.length];
        int stringFromEnd = 0;
        for (int i = types.length - 1; i >= 0; i--) {
            Class<?> type = types[i];
            if (Bundle.class.isAssignableFrom(type)) {
                values[i] = extras;
            } else if (type == String.class) {
                if (stringFromEnd == 0) values[i] = null; // arg
                else if (stringFromEnd == 1) values[i] = METHOD_SEND_BINDER;
                else if (stringFromEnd == 2) values[i] = authority;
                else values[i] = i == 0 ? CALLING_PACKAGE : null;
                stringFromEnd++;
            } else if ("android.content.AttributionSource".equals(type.getName())) {
                values[i] = buildAttributionSource();
            } else if (type == int.class || type == Integer.TYPE) {
                values[i] = SHELL_UID;
            } else if (type == long.class || type == Long.TYPE) {
                values[i] = 0L;
            } else if (type == boolean.class || type == Boolean.TYPE) {
                values[i] = false;
            } else {
                values[i] = null;
            }
        }
        return values;
    }

    private Object buildAttributionSource() throws Exception {
        Class<?> builderClass = Class.forName("android.content.AttributionSource$Builder");
        Constructor<?> constructor = builderClass.getConstructor(int.class);
        Object builder = constructor.newInstance(SHELL_UID);
        try {
            Method setPackageName = builderClass.getMethod("setPackageName", String.class);
            setPackageName.invoke(builder, CALLING_PACKAGE);
        } catch (Throwable ignored) {
        }
        Method build = builderClass.getMethod("build");
        return build.invoke(builder);
    }

    private void removeContentProviderExternal(Object activityManager) {
        for (Method method : activityManagerMethods(activityManager)) {
            if (!"removeContentProviderExternal".equals(method.getName())) continue;
            try {
                method.setAccessible(true);
                Class<?>[] types = method.getParameterTypes();
                Object[] arguments = new Object[types.length];
                int stringIndex = 0;
                for (int i = 0; i < types.length; i++) {
                    Class<?> type = types[i];
                    if (type == String.class) arguments[i] = stringIndex++ == 0 ? authority : "time_machine_server";
                    else if (IBinder.class.isAssignableFrom(type)) arguments[i] = providerToken;
                    else if (type == int.class || type == Integer.TYPE) arguments[i] = userId;
                    else if (type == boolean.class || type == Boolean.TYPE) arguments[i] = false;
                    else arguments[i] = null;
                }
                method.invoke(activityManager, arguments);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName())) return true;
        }
        return false;
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Method setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            getRuntime.setAccessible(true);
            setHiddenApiExemptions.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }

    private static Result setTime(long targetMillis) {
        if (targetMillis <= 0L) return new Result(false, "invalid time");

        CommandResult autoOff = shell("settings put global auto_time 0");
        CommandResult alarm = shell("cmd alarm set-time " + targetMillis);
        CommandResult set = alarm.exitCode == 0 ? alarm : shell("date -s @" + (targetMillis / 1000L));
        CommandResult automatic = shell("settings get global auto_time");
        CommandResult now = shell("date +%s");
        long current = -1L;
        try {
            current = Long.parseLong(now.output.trim()) * 1000L;
        } catch (Throwable ignored) {
        }

        boolean ok = autoOff.exitCode == 0 && set.exitCode == 0 && "0".equals(automatic.output.trim()) && current > 0L && Math.abs(current - targetMillis) <= 90000L;
        return ok
                ? new Result(true, "time set")
                : new Result(false, "auto=" + clean(automatic.output) + " alarm=" + alarm.exitCode + " set=" + set.exitCode + " now=" + current);
    }

    private static Result setAutomaticTime(boolean enabled) {
        String value = enabled ? "1" : "0";
        CommandResult change = shell("settings put global auto_time " + value);
        CommandResult actual = shell("settings get global auto_time");
        boolean ok = change.exitCode == 0 && value.equals(actual.output.trim());
        return ok
                ? new Result(true, "auto_time=" + value)
                : new Result(false, "auto=" + clean(actual.output) + " exit=" + change.exitCode);
    }

    private static CommandResult shell(String command) {
        try {
            java.lang.Process process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append('\n');
                output.append(line);
            }
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, output.toString());
        } catch (Throwable failure) {
            return new CommandResult(-1, String.valueOf(failure));
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String result = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return result.substring(0, Math.min(result.length(), 500));
    }

    private static final class Result {
        final boolean success;
        final String detail;

        Result(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
