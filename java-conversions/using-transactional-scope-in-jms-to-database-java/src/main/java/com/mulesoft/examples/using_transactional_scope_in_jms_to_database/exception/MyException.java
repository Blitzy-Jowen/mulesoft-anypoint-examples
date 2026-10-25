/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.exception;

/**
 * Thrown by the order-processing component inside the JMS-to-database transaction to force its rollback.
 * It stays a checked exception, as in the original; the transaction therefore declares rollback on any
 * {@code Exception}, since Spring rolls back only on unchecked exceptions by default.
 */
public class MyException extends Exception {

    private static final long serialVersionUID = 1123544523432L;

    String mistake;

    public MyException() {
        super();
        mistake = "test";
    }

    public MyException(String err) {
        super(err);
        mistake = err;
    }

    public String getError() {
        return mistake;
    }

}
