//
// JSContext.java
//
// AndroidJSCore project
// https://github.com/ericwlange/AndroidJSCore/
//
// LiquidPlayer project
// https://github.com/LiquidPlayer
//
// Created by Eric Lange
//
/*
 Copyright (c) 2014-2016 Eric Lange. All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

 - Redistributions of source code must retain the above copyright notice, this
 list of conditions and the following disclaimer.

 - Redistributions in binary form must reproduce the above copyright notice,
 this list of conditions and the following disclaimer in the documentation
 and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package org.liquidplayer.javascript;

import android.support.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.Semaphore;

/**
 * Wraps a JavaScriptCore context
 */
@SuppressWarnings("WeakerAccess,SameParameterValue")
public class JSContext extends JSObject {

    static {
        System.loadLibrary("node");
        System.loadLibrary("liquidcore");
    }

    static void init() {
    }

    public void sync(final Runnable runnable) {
        if (isOnThread()) {
            runnable.run();
        } else {
            final Semaphore sempahore = new Semaphore(0);
            Runnable syncRunner = new Runnable() {
                @Override
                public void run() {
                    runnable.run();
                    sempahore.release();
                }
            };
            getGroup().schedule(syncRunner);
            sempahore.acquireUninterruptibly();
        }
    }

    private boolean isOnThread() {
        return ctxRef == null || getGroup().isOnThread();
    }

    public long getJSCContext() {
        return 0L;
    }

    private JSContextGroup contextGroup = null;

    /**
     * Object interface for handling JSExceptions.
     * @since 0.1.0
     */
    public interface IJSExceptionHandler {
        /**
         * Implement this method to catch JSExceptions
         * @param exception caught exception
         * @since 0.1.0
         */
        void handle(JSException exception);
    }

    private JNIJSContext ctxRef;
    private IJSExceptionHandler exceptionHandler;

    protected JSContext(long ctxHandle, JSContextGroup group) {
        context = this;
        contextGroup = group;
        ctxRef = JNIJSContext.fromRef(ctxHandle);
        if (ctxRef != null) {
            valueRef = ctxRef.getGlobalObject();
        }
        addJSExports();
    }

    /**
     * Creates a new JavaScript context
     * @since 0.1.0
     */
    public JSContext() {
        this(new JSContextGroup());
    }
    /**
     * Creates a new JavaScript context in the context group 'inGroup'.
     * @param inGroup  The context group to create the context in
     * @since 0.1.0
     */
    public JSContext(final JSContextGroup inGroup) {
        context = this;
        contextGroup = inGroup;

        ctxRef = JNIJSContext.createContext(inGroup.groupRef());
        valueRef = ctxRef.getGlobalObject();
        addJSExports();
    }
    /**
     * Creates a JavaScript context, and defines the global object with interface 'iface'.  This
     * object must implement 'iface'.  The methods in 'iface' will be exposed to the JavaScript environment.
     * @param iface  The interface to expose to JavaScript
     * @since 0.1.0
     */
    public JSContext(final Class<?> iface) {
        this(new JSContextGroup(), iface);
    }
    /**
     * Creates a JavaScript context in context group 'inGroup', and defines the global object
     * with interface 'iface'.  This object must implement 'iface'.  The methods in 'iface' will
     * be exposed to the JavaScript environment.
     * @param inGroup  The context group to create the context in
     * @param iface  The interface to expose to JavaScript
     * @since 0.1.0
     */
    public JSContext(final JSContextGroup inGroup, final Class<?> iface) {
        this(inGroup);
        Method[] methods = iface.getDeclaredMethods();
        for (Method m : methods) {
            JSObject f = new JSFunction(context, m, JSObject.class, context);
            property(m.getName(),f);
        }
    }

    /**
     * Sets the JS exception handler for this context.  Any thrown JSException in this
     * context will call the 'handle' method on this object.  The calling function will
     * return with an undefined value.
     * @param handler An object that implements 'IJSExceptionHandler'
     * @since 0.1.0
     */
    public void setExceptionHandler(IJSExceptionHandler handler) {
        exceptionHandler = handler;
    }

    /**
     * Clears a previously set exception handler.
     * @since 0.1.0
     */
    public void clearExceptionHandler() {
        exceptionHandler = null;
    }

    /**
     * If an exception handler is set, calls the exception handler, otherwise throws
     * the JSException.
     * @param exception The JSException to be thrown
     * @since 0.1.0
     */
    public void throwJSException(JSException exception) {
        if (exceptionHandler == null) {
            throw exception;
        } else {
            // Before handling this exception, disable the exception handler.  If a JSException
            // is thrown in the handler, then it would recurse and blow the stack.  This way an
            // actual exception will get thrown.  If successfully handled, then turn it back on.
            IJSExceptionHandler temp = exceptionHandler;
            exceptionHandler = null;
            temp.handle(exception);
            exceptionHandler = temp;
        }
    }

    /**
     * Gets the context group to which this context belongs.
     * @return  The context group to which this context belongs
     */
    public JSContextGroup getGroup() {
        if (contextGroup == null) {
            JNIJSContextGroup g = ctxRef.getGroup();
            if (g == null) return null;
            contextGroup = new JSContextGroup(g);
        }
        return contextGroup;
    }

    /**
     * Gets the JavaScriptCore context reference
     * @return  the JavaScriptCore context reference
     * @since 0.1.0
     */
    public JNIJSContext ctxRef() {
        return ctxRef;
    }

    private abstract class JNIReturnClass implements Runnable {
        JNIJSValue value;
        JNIJSValue exception;
    }

    /**
     * Executes a the JavaScript code in 'script' in this context
     * @param script  The code to execute
     * @param sourceURL  The URI of the source file, only used for reporting in stack trace (optional)
     * @param startingLineNumber  The beginning line number, only used for reporting in stack trace (optional)
     * @return  The return value returned by 'script'
     * @since 0.1.0
     */
    public JSValue evaluateScript(final @NonNull String script,
                                  final String sourceURL, final int startingLineNumber) {

        String src = (sourceURL==null) ? "<code>" : sourceURL;
        try {
            return new JSValue(ctxRef.evaluateScript(script, src, startingLineNumber), context);
        } catch (JNIJSException excp){
            throwJSException(new JSException(new JSValue(excp.exception, context)));
            return new JSValue(this);
        }
    }

    /**
     * Executes a the JavaScript code in 'script' in this context
     * @param script  The code to execute
     * @return  The return value returned by 'script'
     * @since 0.1.0
     */
    public JSValue evaluateScript(String script) {
        return evaluateScript(script,null,0);
    }

    private final HashMap<Long, WeakReference<JSObject>> objects = new HashMap<>();

    /**
     * Keeps a reference to an object in this context.  This is used so that only one
     * Java object instance wrapping a JavaScript object is maintained at any time.  This way,
     * local variables in the Java object will stay wrapped around all returns of the same
     * instance.  This is handled by JSObject, and should not need to be called by clients.
     * @param obj  The object with which to associate with this context
     * @since 0.1.0
     */
    void persistObject(JSObject obj) {
        objects.put(obj.valueRef().reference, new WeakReference<>(obj));
    }
    /**
     * Removes a reference to an object in this context.  Should only be used from the 'finalize'
     * object method.  This is handled by JSObject, and should not need to be called by clients.
     * @param obj the JSObject to dereference
     * @since 0.1.0
     */
    void finalizeObject(JSObject obj) {
        objects.remove(obj.valueRef().reference);
    }
    /**
     * Reuses a stored reference to a JavaScript object if it exists, otherwise, it creates the
     * reference.
     * @param objRef the JavaScriptCore object reference
     * @param create whether to create the object if it does not exist
     * @since 0.1.0
     * @return The JSObject representing the reference
     */
    JSObject getObjectFromRef(final JNIJSObject objRef, boolean create) {
        if (objRef.equals(valueRef())) {
            return this;
        }
        WeakReference<JSObject> wr = objects.get(objRef.reference);
        JSObject obj = null;
        if (wr != null) {
            obj = wr.get();
        }
        if (obj==null && create) {
            obj = new JSObject(objRef,this);
            if (obj.isArray()) {
                obj = new JSArray(objRef, this);
            } else if (obj.isTypedArray()) {
                obj = JSTypedArray.from(obj);
            } else if (obj.isFunction()) {
                obj = new JSFunction(objRef, this);
            }
        }
        return obj;
    }
    JSObject getObjectFromRef(JNIJSObject objRef) {
        return getObjectFromRef(objRef,true);
    }
}
