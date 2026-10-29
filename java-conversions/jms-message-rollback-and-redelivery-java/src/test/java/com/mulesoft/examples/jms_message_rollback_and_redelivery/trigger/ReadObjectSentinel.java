/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Harmless stand-in for a deserialization gadget: a serializable class whose {@code readObject} only records that it
 * ran. A test that sends it in an object message body can tell whether reading that body executed code of a class the
 * sender chose, which a deserialization filter must prevent before any instance is created.
 */
final class ReadObjectSentinel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Set once {@link #readObject(ObjectInputStream)} of any instance has run; each test resets it. */
    static final AtomicBoolean READ = new AtomicBoolean();

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        READ.set(true);
    }
}
