package org.apache.tomee.patch.plugin;


import org.apache.maven.plugin.logging.Log;

public class MavenLog implements org.apache.tomee.patch.core.Log {
    private final Log log;

    public MavenLog(final Log log) {
        this.log = log;
    }

    public boolean isDebugEnabled() {
        return log.isDebugEnabled();
    }

    public void debug(final CharSequence charSequence) {
        log.debug(charSequence);
    }

    public void debug(final CharSequence charSequence, final Throwable throwable) {
        log.debug(charSequence, throwable);
    }

    public void debug(final Throwable throwable) {
        log.debug(throwable);
    }

    public boolean isInfoEnabled() {
        return log.isInfoEnabled();
    }

    public void info(final CharSequence charSequence) {
        log.info(charSequence);
    }

    public void info(final CharSequence charSequence, final Throwable throwable) {
        log.info(charSequence, throwable);
    }

    public void info(final Throwable throwable) {
        log.info(throwable);
    }

    public boolean isWarnEnabled() {
        return log.isWarnEnabled();
    }

    public void warn(final CharSequence charSequence) {
        log.warn(charSequence);
    }

    public void warn(final CharSequence charSequence, final Throwable throwable) {
        log.warn(charSequence, throwable);
    }

    public void warn(final Throwable throwable) {
        log.warn(throwable);
    }

    public boolean isErrorEnabled() {
        return log.isErrorEnabled();
    }

    public void error(final CharSequence charSequence) {
        log.error(charSequence);
    }

    public void error(final CharSequence charSequence, final Throwable throwable) {
        log.error(charSequence, throwable);
    }

    public void error(final Throwable throwable) {
        log.error(throwable);
    }
}
