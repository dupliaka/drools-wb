/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.workbench.screens.scenariosimulation.backend.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.enterprise.context.ApplicationScoped;

import org.drools.scenariosimulation.api.model.Settings;
import org.drools.scenariosimulation.backend.util.DMNSimulationUtils;
import org.drools.workbench.screens.scenariosimulation.backend.server.exceptions.WrongDMNTypeException;
import org.drools.workbench.screens.scenariosimulation.model.typedescriptor.FactModelTree;
import org.drools.workbench.screens.scenariosimulation.model.typedescriptor.FactModelTuple;
import org.drools.workbench.screens.scenariosimulation.service.DMNTypeService;
import org.guvnor.common.services.backend.exceptions.ExceptionUtilities;
import org.jboss.errai.bus.server.annotations.Service;
import org.kie.api.runtime.KieContainer;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.api.core.DMNType;
import org.kie.dmn.api.core.ast.DecisionNode;
import org.kie.dmn.api.core.ast.InputDataNode;
import org.kie.dmn.core.impl.BaseDMNTypeImpl;
import org.kie.dmn.feel.lang.Type;
import org.kie.dmn.feel.lang.types.BuiltInType;
import org.uberfire.backend.vfs.Path;

import static org.drools.scenariosimulation.api.utils.ConstantsHolder.VALUE;
import static org.drools.workbench.screens.scenariosimulation.backend.server.util.DMNUtils.getRootType;

@Service
@ApplicationScoped
public class DMNTypeServiceImpl
        extends AbstractKieContainerService
        implements DMNTypeService {

    @Override
    public FactModelTuple retrieveFactModelTuple(Path path, String dmnPath) {
        DMNModel dmnModel = getDMNModel(path, dmnPath);
        SortedMap<String, FactModelTree> visibleFacts = new TreeMap<>();
        SortedMap<String, FactModelTree> hiddenFacts = new TreeMap<>();
        ErrorHolder errorHolder = new ErrorHolder();
        for (InputDataNode input : dmnModel.getInputs()) {
            DMNType type = input.getType();
            checkTypeSupport(type, errorHolder, input.getName());
            try {
                visibleFacts.put(input.getName(), createTopLevelFactModelTree(input.getName(), type, hiddenFacts, FactModelTree.Type.INPUT));
            } catch (WrongDMNTypeException e) {
                throw ExceptionUtilities.handleException(e);
            }
        }
        for (DecisionNode decision : dmnModel.getDecisions()) {
            DMNType type = decision.getResultType();
            checkTypeSupport(type, errorHolder, decision.getName());
            try {
                visibleFacts.put(decision.getName(), createTopLevelFactModelTree(decision.getName(), type, hiddenFacts, FactModelTree.Type.DECISION));
            } catch (WrongDMNTypeException e) {
                throw ExceptionUtilities.handleException(e);
            }
        }
        FactModelTuple factModelTuple = new FactModelTuple(visibleFacts, hiddenFacts);
        errorHolder.getMultipleNestedCollection().forEach(factModelTuple::addMultipleNestedCollectionError);
        errorHolder.getMultipleNestedObject().forEach(factModelTuple::addMultipleNestedObjectError);
        return factModelTuple;
    }

    @Override
    public void initializeNameAndNamespace(Settings settings, Path path, String dmnPath) {
        DMNModel dmnModel = getDMNModel(path, dmnPath);
        settings.setDmnName(dmnModel.getName());
        settings.setDmnNamespace(dmnModel.getNamespace());
    }

    public DMNModel getDMNModel(Path path, String dmnPath) {
        return DMNSimulationUtils.extractDMNModel(getDMNRuntime(path), dmnPath);
    }

    public DMNRuntime getDMNRuntime(Path path) {
        KieContainer kieContainer = getKieContainer(path);
        return DMNSimulationUtils.extractDMNRuntime(kieContainer);
    }

    /**
     * This method is the <b>entry point</b> for <code>FactModelTree</code>. It is the one to be called from the very top level <code>DMNType</code>
     * @param factName
     * @param type
     * @param hiddenFacts
     * @param fmType
     * @return
     * @throws WrongDMNTypeException
     */
    protected FactModelTree createTopLevelFactModelTree(String factName, DMNType type, SortedMap<String, FactModelTree> hiddenFacts, FactModelTree.Type fmType) throws WrongDMNTypeException {
        return isToBeManagedAsCollection(type) ? createFactModelTreeForCollection(new HashMap<>(), factName, type, hiddenFacts, fmType, new ArrayList<>()) : createFactModelTreeForNoCollection(new HashMap<>(), factName, factName, type.getName(), type, hiddenFacts, fmType, new ArrayList<>());
    }

    /**
     * Creates a <code>FactModelTree</code> for <code>DMNType</code> where <code>DMNType.isCollection()</code> == <code>true</code>
     * @param factName
     * @param type
     * @param hiddenFacts
     * @param fmType
     * @return
     * @throws WrongDMNTypeException if <code>DMNType.isCollection()</code> != <code>true</code>
     */
    protected FactModelTree createFactModelTreeForCollection(Map<String, List<String>> genericTypeInfoMap, String factName, DMNType type, SortedMap<String, FactModelTree> hiddenFacts, FactModelTree.Type fmType, List<String> alreadyVisited) throws WrongDMNTypeException {
        if (!type.isCollection() && !isToBeManagedAsCollection(type)) {
            throw new WrongDMNTypeException();
        }
        String typeName = type.getName();
        populateGeneric(genericTypeInfoMap, VALUE, typeName);
        FactModelTree toReturn = createFactModelTreeForSimple(genericTypeInfoMap, factName, List.class.getCanonicalName(), fmType);
        if (!hiddenFacts.containsKey(typeName) && !alreadyVisited.contains(typeName)) {
            alreadyVisited.add(typeName);
            FactModelTree genericFactModelTree = createFactModelTreeForGenericType(new HashMap<>(), typeName, typeName, typeName, type, hiddenFacts, fmType, alreadyVisited);
            hiddenFacts.put(typeName, genericFactModelTree);
        }
        return toReturn;
    }

    /**
     * Creates a <code>FactModelTree</code> for <code>DMNType</code> where <code>DMNType.isCollection()</code> != <code>true</code>
     * @param propertyName
     * @param fullPropertyPath
     * @param type
     * @param hiddenFacts
     * @param fmType
     * @return
     * @throws WrongDMNTypeException if <code>DMNType.isCollection()</code> == <code>true</code>
     */
    protected FactModelTree createFactModelTreeForNoCollection(Map<String, List<String>> genericTypeInfoMap, String factName, String propertyName, String fullPropertyPath, DMNType type, SortedMap<String, FactModelTree> hiddenFacts, FactModelTree.Type fmType, List<String> alreadyVisited) throws WrongDMNTypeException {
        if (type.isCollection() && isToBeManagedAsCollection(type)) {
            throw new WrongDMNTypeException();
        }
        return isToBeManagedAsComposite(type) ? createFactModelTreeForComposite(genericTypeInfoMap, propertyName, fullPropertyPath, type, hiddenFacts, fmType, alreadyVisited) : createFactModelTreeForSimple(genericTypeInfoMap, factName, type.getName(), fmType);
    }

    /**
     * Creates a <code>FactModelTree</code> for <code>DMNType</code>
     * @param propertyName
     * @param fullPropertyPath
     * @param type
     * @param hiddenFacts
     * @param fmType
     * @return
     */
    protected FactModelTree createFactModelTreeForGenericType(Map<String, List<String>> genericTypeInfoMap, String factName, String propertyName, String fullPropertyPath, DMNType type, SortedMap<String, FactModelTree> hiddenFacts, FactModelTree.Type fmType, List<String> alreadyVisited) throws WrongDMNTypeException {
        return type.isComposite() ? createFactModelTreeForComposite(genericTypeInfoMap, propertyName, fullPropertyPath, type, hiddenFacts, fmType, alreadyVisited) : createFactModelTreeForSimple(genericTypeInfoMap, factName, type.getName(), fmType);
    }

    /**
     * Creates a <code>FactModelTree</code> for <code>DMNType</code> where <code>DMNType.isComposite()</code> != <code>true</code>.
     * Returned <code>FactModelTree</code> will have only one single property, whose name is <b>VALUE</b> and whose value is the given <b>propertyClass</b>
     * @param genericTypeInfoMap
     * @param factName
     * @param propertyClass
     * @param fmType
     * @return
     */
    protected FactModelTree createFactModelTreeForSimple(Map<String, List<String>> genericTypeInfoMap, String factName, String propertyClass, FactModelTree.Type fmType) {
        Map<String, String> simpleProperties = new HashMap<>();
        FactModelTree simpleFactModelTree = new FactModelTree(factName, "", simpleProperties, genericTypeInfoMap, fmType);
        simpleFactModelTree.addSimpleProperty(VALUE, propertyClass);
        simpleFactModelTree.setSimple(true);
        return simpleFactModelTree;
    }

    /**
     * Creates a <code>FactModelTree</code> for <code>DMNType</code> where <code>DMNType.isComposite()</code> == <code>true</code>
     * @param name
     * @param fullPropertyPath
     * @param type
     * @param hiddenFacts
     * @param fmType
     * @throws WrongDMNTypeException if <code>DMNType.isComposite()</code> != <code>true</code>
     */
    protected FactModelTree createFactModelTreeForComposite(Map<String, List<String>> genericTypeInfoMap, String name, String fullPropertyPath, DMNType type, SortedMap<String, FactModelTree> hiddenFacts, FactModelTree.Type fmType, List<String> alreadyVisited) throws WrongDMNTypeException {
        if (!type.isComposite() && !isToBeManagedAsComposite(type)) {
            throw new WrongDMNTypeException();
        }
        Map<String, String> simpleFields = new HashMap<>();
        FactModelTree toReturn = new FactModelTree(name, "", simpleFields, genericTypeInfoMap, fmType);
        for (Map.Entry<String, DMNType> entry : type.getFields().entrySet()) {
            String expandablePropertyName = fullPropertyPath + "." + entry.getKey();
            if (isToBeManagedAsCollection(entry.getValue())) {  // if it is a collection, generate the generic and add as hidden fact a simple or composite fact model tree
                FactModelTree fact = createFactModelTreeForCollection(new HashMap<>(), entry.getKey(), entry.getValue(), hiddenFacts, FactModelTree.Type.UNDEFINED, alreadyVisited);
                simpleFields.put(entry.getKey(), List.class.getCanonicalName());
                genericTypeInfoMap.put(entry.getKey(), fact.getGenericTypeInfo(VALUE));
            } else {
                String typeName = entry.getValue().getName();
                if (entry.getValue().isComposite()) { // a complex type needs the expandable property and then in the hidden map, its fact model tree
                    if (!hiddenFacts.containsKey(typeName) && !alreadyVisited.contains(typeName)) {
                        alreadyVisited.add(typeName);
                        FactModelTree fact = createFactModelTreeForNoCollection(genericTypeInfoMap, entry.getKey(), VALUE, expandablePropertyName, entry.getValue(), hiddenFacts, FactModelTree.Type.UNDEFINED, alreadyVisited);
                        hiddenFacts.put(typeName, fact);
                    }
                    toReturn.addExpandableProperty(entry.getKey(), typeName);
                } else {  // a simple type is just name -> type
                    simpleFields.put(entry.getKey(), typeName);
                }
            }
        }
        return toReturn;
    }

    /**
     * Return <code>true</code> if the given <code>DMNType</code> has to be managed as <b>collection</b>
     * @param type
     * @return <code>true</code> if <code>DMNType.isCollection() == true</code> <b>and</b> <code>BaseDMNTypeImpl.getFeelType() != BuiltInType.UNKNOWN</code>
     */
    private boolean isToBeManagedAsCollection(DMNType type) {
        boolean toReturn = type.isCollection();
        if (toReturn) {
            Type feelType = getRootType((BaseDMNTypeImpl) type);
            // BuiltInType.UNKNOWN is a special case: it is instantiated as collection but it should be considered as single for editing
            if (feelType instanceof BuiltInType && feelType.equals(BuiltInType.UNKNOWN)) {
                toReturn = false;
            }
        }
        return toReturn;
    }

    /**
     * Return <code>true</code> if the given <code>DMNType</code> has to be managed as <b>composite</b>
     * @param type
     * @return <code>true</code> if <code>DMNType.isCollection() == true</code> <b>and</b> <code>BaseDMNTypeImpl.getFeelType() != BuiltInType.UNKNOWN</code>
     */
    private boolean isToBeManagedAsComposite(DMNType type) {
        boolean toReturn = type.isComposite();
        if (toReturn) {
            Type feelType = getRootType((BaseDMNTypeImpl) type);
            // BuiltInType.CONTEXT is a special case: it is instantiated as composite but has no nested fields so it should be considered as simple for editing
            if (feelType instanceof BuiltInType && feelType.equals(BuiltInType.CONTEXT)) {
                toReturn = false;
            }
        }
        return toReturn;
    }

    /**
     * This method map the given <b>name</b> to <b>List.class.getCanonicalName()</b> inside <b>simpleFields</b>,
     * and map <b>name</b>  to a singleton list containing a newly generated <b>key</b> (path + "." + type) inside <b>genericTypeInfoMap</b>
     * @param genericTypeInfoMap
     * @param fullPropertyPath
     * @param type
     * @return
     */
    private String populateGeneric(Map<String, List<String>> genericTypeInfoMap,
                                   String fullPropertyPath,
                                   String type) {
        String genericKey = fullPropertyPath;
        genericTypeInfoMap.put(fullPropertyPath, Collections.singletonList(type));
        return genericKey;
    }

    /**
     * Recursively visit a <i>composite</i> <code>DMNType</code> to eventually detect and add errors to given <code>ErrorHolder</code>
     * @param type
     * @param errorHolder
     * @param fullPropertyPath
     */
    protected void checkTypeSupport(DMNType type,
                                    ErrorHolder errorHolder,
                                    String fullPropertyPath) {
        internalCheckTypeSupport(type, false, errorHolder, fullPropertyPath, new HashSet<>());
    }

    protected void internalCheckTypeSupport(DMNType type,
                                            boolean alreadyInCollection,
                                            ErrorHolder errorHolder,
                                            String fullPropertyPath,
                                            Set<String> alreadyVisited) {
        alreadyVisited.add(type.getName());
        if (type.isComposite()) {
            for (Map.Entry<String, DMNType> entry : type.getFields().entrySet()) {
                String name = entry.getKey();
                DMNType nestedType = entry.getValue();
                String nestedPath = fullPropertyPath + "." + name;
                if (alreadyInCollection && nestedType.isCollection()) {
                    errorHolder.getMultipleNestedCollection().add(nestedPath);
                }
                if (alreadyInCollection && nestedType.isComposite()) {
                    errorHolder.getMultipleNestedObject().add(nestedPath);
                }
                if (!alreadyVisited.contains(nestedType.getName())) {
                    internalCheckTypeSupport(nestedType,
                                             alreadyInCollection || nestedType.isCollection(),
                                             errorHolder,
                                             nestedPath,
                                             alreadyVisited);
                }
            }
        }
    }

    static class ErrorHolder {

        Set<String> multipleNestedObject = new TreeSet<>();
        Set<String> multipleNestedCollection = new TreeSet<>();

        public Set<String> getMultipleNestedObject() {
            return multipleNestedObject;
        }

        public Set<String> getMultipleNestedCollection() {
            return multipleNestedCollection;
        }
    }
}