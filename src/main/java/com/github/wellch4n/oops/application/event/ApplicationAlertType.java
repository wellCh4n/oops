package com.github.wellch4n.oops.application.event;

public enum ApplicationAlertType {

    /** Usage crossed the threshold and stayed there. Also used for the periodic reminder while it is still firing. */
    FIRING,

    /** Usage came back under the threshold. */
    RESOLVED
}
