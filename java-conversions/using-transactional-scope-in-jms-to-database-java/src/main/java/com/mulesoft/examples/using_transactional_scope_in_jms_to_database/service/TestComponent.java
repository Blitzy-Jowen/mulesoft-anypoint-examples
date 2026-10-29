/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.exception.MyException;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import org.springframework.stereotype.Component;

/**
 * The "Throw an exception" component of the transactional scope: it runs after the order insert and before the
 * send to queue {@code out}, and always fails, so the database insert and the JMS message roll back together.
 */
@Component
public class TestComponent {

    /**
     * Always throws, whatever the payload (including {@code null}).
     *
     * @param payload the order being processed; not read
     * @return never returns normally
     * @throws MyException always, with the message {@code exception customized}
     */
    public Object onCall(Order payload) throws MyException {
        throw new MyException("exception customized");
    }

}
