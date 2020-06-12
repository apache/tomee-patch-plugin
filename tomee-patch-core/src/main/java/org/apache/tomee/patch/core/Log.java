package org.apache.tomee.patch.core;

public interface Log {
    boolean isDebugEnabled();

    void debug(CharSequence var1);

    void debug(CharSequence var1, Throwable var2);

    void debug(Throwable var1);

    boolean isInfoEnabled();

    void info(CharSequence var1);

    void info(CharSequence var1, Throwable var2);

    void info(Throwable var1);

    boolean isWarnEnabled();

    void warn(CharSequence var1);

    void warn(CharSequence var1, Throwable var2);

    void warn(Throwable var1);

    boolean isErrorEnabled();

    void error(CharSequence var1);

    void error(CharSequence var1, Throwable var2);

    void error(Throwable var1);
}
