/*
 * Copyright 2000-2014 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.vaadin.event.Registration;
import com.vaadin.server.UserError;
import com.vaadin.ui.AbstractComponent;

/**
 * Connects one or more {@code Field} components to properties of a backing data
 * type such as a bean type. With a binder, input components can be grouped
 * together into forms to easily create and update business objects with little
 * explicit logic needed to move data between the UI and the data layers of the
 * application.
 * <p>
 * A binder is a collection of <i>bindings</i>, each representing the
 * association of a single field and a backing property.
 * <p>
 * A binder instance can be bound to a single bean instance at a time, but can
 * be rebound as needed. This allows usage patterns like a <i>master-details</i>
 * view, where a select component is used to pick the bean to edit.
 * <p>
 * Unless otherwise specified, {@code Binder} method arguments cannot be null.
 *
 * @author Vaadin Ltd.
 *
 * @param <BEAN>
 *            the bean type
 *
 * @see Binding
 * @see HasValue
 *
 * @since
 */
public class Binder<BEAN> implements Serializable {

    /**
     * Represents the binding between a field and a data property.
     *
     * @param <BEAN>
     *            the bean type
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param <TARGET>
     *            the target data type of the binding, matches the field type
     *            until a converter has been set
     *
     * @see Binder#forField(HasValue)
     */
    public interface Binding<BEAN, FIELDVALUE, TARGET> extends Serializable {

        /**
         * Completes this binding using the given getter and setter functions
         * representing a backing bean property. The functions are used to
         * update the field value from the property and to store the field value
         * to the property, respectively.
         * <p>
         * When a bean is bound with {@link Binder#bind(BEAN)}, the field value
         * is set to the return value of the given getter. The property value is
         * then updated via the given setter whenever the field value changes.
         * The setter may be null; in that case the property value is never
         * updated and the binding is said to be <i>read-only</i>.
         * <p>
         * If the Binder is already bound to some item, the newly bound field is
         * associated with the corresponding bean property as described above.
         * <p>
         * The getter and setter can be arbitrary functions, for instance
         * implementing user-defined conversion or validation. However, in the
         * most basic use case you can simply pass a pair of method references
         * to this method as follows:
         *
         * <pre>
         * class Person {
         *     public String getName() { ... }
         *     public void setName(String name) { ... }
         * }
         *
         * TextField nameField = new TextField();
         * binder.forField(nameField).bind(Person::getName, Person::setName);
         * </pre>
         *
         * @param getter
         *            the function to get the value of the property to the
         *            field, not null
         * @param setter
         *            the function to save the field value to the property or
         *            null if read-only
         * @throws IllegalStateException
         *             if {@code bind} has already been called on this binding
         */
        public void bind(Function<BEAN, TARGET> getter,
                BiConsumer<BEAN, TARGET> setter);

        /**
         * Adds a validator to this binding. Validators are applied, in
         * registration order, when the field value is saved to the backing
         * property. If any validator returns a failure, the property value is
         * not updated.
         *
         * @param validator
         *            the validator to add, not null
         * @return this binding, for chaining
         * @throws IllegalStateException
         *             if {@code bind} has already been called
         */
        public Binding<BEAN, FIELDVALUE, TARGET> withValidator(
                Validator<? super TARGET> validator);

        /**
         * A convenience method to add a validator to this binding using the
         * {@link Validator#from(Predicate, String)} factory method.
         * <p>
         * Validators are applied, in registration order, when the field value
         * is saved to the backing property. If any validator returns a failure,
         * the property value is not updated.
         *
         * @see #withValidator(Validator)
         * @see Validator#from(Predicate, String)
         *
         * @param predicate
         *            the predicate performing validation, not null
         * @param message
         *            the error message to report in case validation failure
         * @return this binding, for chaining
         * @throws IllegalStateException
         *             if {@code bind} has already been called
         */
        public Binding<BEAN, FIELDVALUE, TARGET> withValidator(
                Predicate<? super TARGET> predicate, String message);

        /**
         * Gets the field the binding uses.
         *
         * @return the field for the binding
         */
        public HasValue<FIELDVALUE> getField();

    }

    /**
     * An internal implementation of {@code Binding}.
     *
     * @param <BEAN>
     *            the bean type, must match the Binder bean type
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param <TARGET>
     *            the target data type of the binding, matches the field type
     *            until a converter has been set
     */
    protected static class BindingImpl<BEAN, FIELDVALUE, TARGET>
            implements Binding<BEAN, FIELDVALUE, TARGET> {

        private Binder<BEAN> binder;

        private HasValue<FIELDVALUE> field;
        private Registration onValueChange;

        private Function<BEAN, TARGET> getter;
        private BiConsumer<BEAN, TARGET> setter;

        private List<Validator<? super TARGET>> validators = new ArrayList<>();

        /**
         * Creates a new binding associated with the given field.
         *
         * @param binder
         *            the binder this instance is connected to
         * @param field
         *            the field to bind
         */
        protected BindingImpl(Binder<BEAN> binder, HasValue<FIELDVALUE> field) {
            this.binder = binder;
            this.field = field;
        }

        @Override
        public void bind(Function<BEAN, TARGET> getter,
                BiConsumer<BEAN, TARGET> setter) {
            checkUnbound();
            Objects.requireNonNull(getter, "getter cannot be null");

            this.getter = getter;
            this.setter = setter;
            binder.bindings.add(this);
            binder.getBean().ifPresent(this::bind);
        }

        @Override
        public Binding<BEAN, FIELDVALUE, TARGET> withValidator(
                Validator<? super TARGET> validator) {
            checkUnbound();
            Objects.requireNonNull(validator, "validator cannot be null");
            validators.add(validator);
            return this;
        }

        @Override
        public Binding<BEAN, FIELDVALUE, TARGET> withValidator(
                Predicate<? super TARGET> predicate, String message) {
            return withValidator(Validator.from(predicate, message));
        }

        private void bind(BEAN bean) {
            setFieldValue(bean);
            onValueChange = field
                    .addValueChangeListener(e -> storeFieldValue(bean));
        }

        private List<ValidationError<FIELDVALUE>> validate() {
            return validators.stream()
                    .map(validator -> validator
                            .apply((TARGET) field.getValue()))
                    .filter(Result::isError)
                    .map(result -> new ValidationError<>(field,
                            result.getMessage().orElse(null)))
                    .collect(Collectors.toList());
        }

        private void unbind() {
            onValueChange.remove();
        }

        /**
         * Sets the field value by invoking the getter function on the given
         * bean.
         *
         * @param bean
         *            the bean to fetch the property value from
         */
        private void setFieldValue(BEAN bean) {
            assert bean != null;
            field.setValue((FIELDVALUE) getter.apply(bean));
        }

        /**
         * Saves the field value by invoking the setter function on the given
         * bean, if the value passes all registered validators.
         *
         * @param bean
         *            the bean to set the property value to
         */
        private void storeFieldValue(BEAN bean) {
            assert bean != null;
            if (setter != null) {
                setter.accept(bean, (TARGET) field.getValue());
            }
        }

        private void checkUnbound() {
            if (this.getter != null) {
                throw new IllegalStateException(
                        "cannot modify binding: already bound to a property");
            }
        }

        @Override
        public HasValue<FIELDVALUE> getField() {
            return field;
        }
    }

    private BEAN bean;

    private Set<BindingImpl<BEAN, ?, ?>> bindings = new LinkedHashSet<>();

    /**
     * Returns an {@code Optional} of the bean that has been bound with
     * {@link #bind}, or an empty optional if a bean is not currently bound.
     *
     * @return the currently bound bean if any
     */
    public Optional<BEAN> getBean() {
        return Optional.ofNullable(bean);
    }

    /**
     * Creates a new binding for the given field. The returned binding may be
     * further configured before invoking
     * {@link Binding#bind(Function, BiConsumer) Binding.bind} which completes
     * the binding. Until {@code Binding.bind} is called, the binding has no
     * effect.
     *
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param field
     *            the field to be bound, not null
     * @return the new binding
     */
    public <FIELDVALUE> Binding<BEAN, FIELDVALUE, FIELDVALUE> forField(
            HasValue<FIELDVALUE> field) {
        return createBinding(field);
    }

    /**
     * Binds a field to a bean property represented by the given getter and
     * setter pair. The functions are used to update the field value from the
     * property and to store the field value to the property, respectively.
     * <p>
     * Use the {@link #forField(HasValue)} overload instead if you want to
     * further configure the new binding.
     * <p>
     * When a bean is bound with {@link Binder#bind(BEAN)}, the field value is
     * set to the return value of the given getter. The property value is then
     * updated via the given setter whenever the field value changes. The setter
     * may be null; in that case the property value is never updated and the
     * binding is said to be <i>read-only</i>.
     * <p>
     * If the Binder is already bound to some item, the newly bound field is
     * associated with the corresponding bean property as described above.
     * <p>
     * The getter and setter can be arbitrary functions, for instance
     * implementing user-defined conversion or validation. However, in the most
     * basic use case you can simply pass a pair of method references to this
     * method as follows:
     *
     * <pre>
     * class Person {
     *     public String getName() { ... }
     *     public void setName(String name) { ... }
     * }
     *
     * TextField nameField = new TextField();
     * binder.bind(nameField, Person::getName, Person::setName);
     * </pre>
     *
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param field
     *            the field to bind, not null
     * @param getter
     *            the function to get the value of the property to the field,
     *            not null
     * @param setter
     *            the function to save the field value to the property or null
     *            if read-only
     */
    public <FIELDVALUE> void bind(HasValue<FIELDVALUE> field,
            Function<BEAN, FIELDVALUE> getter,
            BiConsumer<BEAN, FIELDVALUE> setter) {
        forField(field).bind(getter, setter);
    }

    /**
     * Binds the given bean to all the fields added to this Binder. To remove
     * the binding, call {@link #unbind()}.
     * <p>
     * When a bean is bound, the field values are updated by invoking their
     * corresponding getter functions. Any changes to field values are reflected
     * back to their corresponding property values of the bean as long as the
     * bean is bound.
     *
     * @param bean
     *            the bean to edit, not null
     */
    public void bind(BEAN bean) {
        Objects.requireNonNull(bean, "bean cannot be null");
        unbind();
        this.bean = bean;
        bindings.forEach(b -> b.bind(bean));
    }

    /**
     * Validates the values of all bound fields and returns the result of the
     * validation as a set of validation errors.
     * <p>
     * Validation is successful if the resulting set is empty.
     *
     * @return the validation result.
     */
    public List<ValidationError<?>> validate() {
        List<ValidationError<?>> resultErrors = new ArrayList<>();
        for (BindingImpl<BEAN, ?, ?> binding : bindings) {
            clearError(binding.getField());
            List<? extends ValidationError<?>> errors = binding.validate();
            resultErrors.addAll(errors);
            if (!errors.isEmpty()) {
                handleError(binding.getField(), errors.get(0).getMessage());
            }
        }
        return resultErrors;
    }

    /**
     * Unbinds the currently bound bean if any. If there is no bound bean, does
     * nothing.
     */
    public void unbind() {
        if (bean != null) {
            bean = null;
            bindings.forEach(BindingImpl::unbind);
        }
    }

    /**
     * Reads the bound property values from the given bean to the corresponding
     * fields. The bean is not otherwise associated with this binder; in
     * particular its property values are not bound to the field value changes.
     * To achieve that, use {@link #bind(BEAN)}.
     *
     * @param bean
     *            the bean whose property values to read, not null
     */
    public void load(BEAN bean) {
        Objects.requireNonNull(bean, "bean cannot be null");
        bindings.forEach(binding -> binding.setFieldValue(bean));

    }

    /**
     * Saves changes from the bound fields to the edited bean. If any value
     * fails validation, no values are saved and an {@code BindingException} is
     * thrown.
     *
     * @param bean
     *            the object to which to save the field values, not null
     * @throws BindingException
     *             if some of the bound field values fail to validate
     */
    public void save(BEAN bean) {
        Objects.requireNonNull(bean, "bean cannot be null");
        bindings.forEach(binding -> binding.storeFieldValue(bean));
    }

    /**
     * Creates a new binding with the given field.
     *
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param field
     *            the field to bind
     * @return the new incomplete binding
     */
    protected <FIELDVALUE> Binding<BEAN, FIELDVALUE, FIELDVALUE> createBinding(
            HasValue<FIELDVALUE> field) {
        Objects.requireNonNull(field, "field cannot be null");
        BindingImpl<BEAN, FIELDVALUE, FIELDVALUE> b = new BindingImpl<>(this,
                field);
        return b;
    }

    /**
     * Clears the error condition of the given field, if any. The default
     * implementation clears the
     * {@link AbstractComponent#setComponentError(ErrorMessage) component error}
     * of the field if it is a Component, otherwise does nothing.
     *
     * @param field
     *            the field with an invalid value
     */
    protected void clearError(HasValue<?> field) {
        if (field instanceof AbstractComponent) {
            ((AbstractComponent) field).setComponentError(null);
        }
    }

    /**
     * Handles a validation error emitted when trying to save the value of the
     * given field. The default implementation sets the
     * {@link AbstractComponent#setComponentError(ErrorMessage) component error}
     * of the field if it is a Component, otherwise does nothing.
     *
     * @param field
     *            the field with the invalid value
     * @param error
     *            the error message to set
     */
    protected void handleError(HasValue<?> field, String error) {
        if (field instanceof AbstractComponent) {
            ((AbstractComponent) field).setComponentError(new UserError(error));
        }
    }

}