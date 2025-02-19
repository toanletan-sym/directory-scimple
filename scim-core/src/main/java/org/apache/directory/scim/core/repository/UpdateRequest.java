/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
 
* http://www.apache.org/licenses/LICENSE-2.0

* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.directory.scim.core.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.flipkart.zjsonpatch.DiffFlags;
import com.flipkart.zjsonpatch.JsonDiff;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.directory.scim.core.json.ObjectMapperFactory;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.spec.filter.*;
import org.apache.directory.scim.spec.filter.attribute.AttributeReference;
import org.apache.directory.scim.spec.patch.PatchOperation;
import org.apache.directory.scim.spec.patch.PatchOperation.Type;
import org.apache.directory.scim.spec.patch.PatchOperationPath;
import org.apache.directory.scim.spec.resources.ScimExtension;
import org.apache.directory.scim.spec.resources.ScimResource;
import org.apache.directory.scim.spec.resources.TypedAttribute;
import org.apache.directory.scim.spec.schema.AttributeContainer;
import org.apache.directory.scim.spec.schema.Schema;
import org.apache.directory.scim.spec.schema.Schema.Attribute;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@EqualsAndHashCode
@ToString
public class UpdateRequest<T extends ScimResource> {
  
  private static final String OPERATION = "op";
  private static final String PATH = "path";
  private static final String VALUE = "value";

  // Create a Jackson ObjectMapper that reads JaxB annotations
  private final ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

  @Getter
  private String id;
  private T resource;
  @Getter
  private T original;
  private List<PatchOperation> patchOperations;
  private boolean initialized = false;

  private Schema schema;

  private PatchHandler patchHandler;

  private SchemaRegistry schemaRegistry;

  private Map<Attribute, Integer> addRemoveOffsetMap = new HashMap<>();

  public UpdateRequest(SchemaRegistry schemaRegistry) {
    this.schemaRegistry = schemaRegistry;
  }

  public UpdateRequest(String id, T original, T resource, SchemaRegistry schemaRegistry) {
    this(schemaRegistry);
    this.id = id;
    this.original = original;
    this.resource = resource;
    this.patchHandler = new PatchHandlerImpl(schemaRegistry);
    this.schema = schemaRegistry.getSchema(original.getBaseUrn());
    initialized = true;
  }

  public UpdateRequest(String id, T original, List<PatchOperation> patchOperations, SchemaRegistry schemaRegistry) {
    this(schemaRegistry);
    this.id = id;
    this.original = original;
    this.patchOperations = patchOperations;
    this.patchHandler = new PatchHandlerImpl(schemaRegistry);
    this.schema = schemaRegistry.getSchema(original.getBaseUrn());

    initialized = true;
  }

  public T getResource() {
    if (!initialized) {
      throw new IllegalStateException("UpdateRequest was not initialized");
    }

    if (resource == null) {
      this.resource = this.patchHandler.apply(this.original, this.patchOperations);
    }

    return this.resource;
  }

  public List<PatchOperation> getPatchOperations() {
    if (!initialized) {
      throw new IllegalStateException("UpdateRequest was not initialized");
    }
    
    if (patchOperations == null) {
      try {
        patchOperations = createPatchOperations(); 
      } catch (IllegalArgumentException | IllegalAccessException | JsonProcessingException e) {
        throw new IllegalStateException("Error creating the patch list", e);
      }
    }
    
    return patchOperations;
  }

  private void sortMultiValuedCollections(Object obj1, Object obj2, AttributeContainer ac) throws IllegalArgumentException {
    for (Attribute attribute : ac.getAttributes()) {
      Schema.AttributeAccessor accessor = attribute.getAccessor();
      if (attribute.isMultiValued()) {
        @SuppressWarnings("unchecked")
        List<Object> collection1 = obj1 != null ? (List<Object>) accessor.get(obj1) : null;
        @SuppressWarnings("unchecked")
        List<Object> collection2 = obj2 != null ? (List<Object>) accessor.get(obj2) : null;
        
        Set<Object> priorities = findCommonElements(collection1, collection2);
        PrioritySortingComparator prioritySortingComparator = new PrioritySortingComparator(priorities);
        if (collection1 != null) {
          Collections.sort(collection1, prioritySortingComparator);
        }
        
        if (collection2 != null) {
          Collections.sort(collection2, prioritySortingComparator);
        }
      } else if (attribute.getType() == Attribute.Type.COMPLEX) {
        Object nextObj1 = obj1 != null ? accessor.get(obj1) : null;
        Object nextObj2 = obj2 != null ? accessor.get(obj2) : null;
        sortMultiValuedCollections(nextObj1, nextObj2, attribute);
      }
    }
  }

  private Set<Object> findCommonElements(List<Object> list1, List<Object> list2) {
    if (list1 == null || list2 == null) {
      return Collections.emptySet();
    }
    
    Set<Object> set1 = new HashSet<>(list1);
    Set<Object> set2 = new HashSet<>(list2);
    
    set1 = set1.stream().map(PrioritySortingComparator::getComparableValue).collect(Collectors.toSet());
    set2 = set2.stream().map(PrioritySortingComparator::getComparableValue).collect(Collectors.toSet());
    
    set1.retainAll(set2);
    return set1;
  }

  /**
   * There is a know issue with the diffing tool that the tool will attempt to move empty arrays. By
   * nulling out the empty arrays during comparison, this will prevent that error from occurring. Because
   * deleting requires the parent node
   * @param node Parent node.
   */
  private static void nullEmptyLists(JsonNode node) {
    List<String> objectsToDelete = new ArrayList<>();
    
    if (node != null) {
      Iterator<Map.Entry<String, JsonNode>> children = node.fields();
      while(children.hasNext()) {
        Map.Entry<String, JsonNode> child = children.next();
        String name = child.getKey();
        JsonNode childNode = child.getValue();
        
        //Attempt to delete children before analyzing 
        if (childNode.isContainerNode()) {
          nullEmptyLists(childNode);
        }
        
        if (childNode instanceof ArrayNode) {
          ArrayNode ar = (ArrayNode)childNode;
          if (ar.size() == 0) {
            objectsToDelete.add(name);
          }
        }
      }
      
      if (!objectsToDelete.isEmpty() && node instanceof ObjectNode) {
        ObjectNode on = (ObjectNode)node;
        for(String name : objectsToDelete) {
          on.remove(name);
        }
      }
    }
  }

  private List<PatchOperation> createPatchOperations() throws IllegalArgumentException, IllegalAccessException, JsonProcessingException {

    sortMultiValuedCollections(this.original, this.resource, schema);
    Map<String, ScimExtension> originalExtensions = this.original.getExtensions();
    Map<String, ScimExtension> resourceExtensions = this.resource.getExtensions();
    Set<String> keys = new HashSet<>();
    keys.addAll(originalExtensions.keySet());
    keys.addAll(resourceExtensions.keySet());
    
    for(String key: keys) {
      Schema extSchema = schemaRegistry.getSchema(key);
      ScimExtension originalExtension = originalExtensions.get(key);
      ScimExtension resourceExtension = resourceExtensions.get(key);
      sortMultiValuedCollections(originalExtension, resourceExtension, extSchema);
    }

    JsonNode node1 = objectMapper.valueToTree(original);
    nullEmptyLists(node1);
    JsonNode node2 = objectMapper.valueToTree(resource);
    nullEmptyLists(node2);
    JsonNode differences = JsonDiff.asJson(node1, node2, DiffFlags.dontNormalizeOpIntoMoveAndCopy());
    
    
    /*
    Commenting out debug statement to prevent PII from appearing in log
    ObjectWriter writer = objMapper.writerWithDefaultPrettyPrinter();
    try {
      log.debug("Original: "+writer.writeValueAsString(node1));
      log.debug("Resource: "+writer.writeValueAsString(node2));
    } catch (IOException e) {
      
    }*/

    /*try {
      log.debug("Differences: " + objMapper.writerWithDefaultPrettyPrinter().writeValueAsString(differences));
    } catch (JsonProcessingException e) {
      log.debug("Unable to debug differences: ", e);
    }*/

    List<PatchOperation> patchOps = convertToPatchOperations(differences);

    /*try {
      log.debug("Patch Ops: " + objMapper.writerWithDefaultPrettyPrinter().writeValueAsString(patchOps));
    } catch (JsonProcessingException e) {
      log.debug("Unable to debug patch ops: ", e);
    }*/

    return patchOps;
  }

  List<PatchOperation> convertToPatchOperations(JsonNode node) throws IllegalArgumentException, IllegalAccessException, JsonProcessingException {
    List<PatchOperation> operations = new ArrayList<>();
    if (node == null) {
      return Collections.emptyList();
    }

    if (!(node instanceof ArrayNode)) {
      throw new RuntimeException("Expecting an instance of a ArrayNode, but got: " + node.getClass());
    }

    ArrayNode root = (ArrayNode) node;
    for (int i = 0; i < root.size(); i++) {
      ObjectNode patchNode = (ObjectNode) root.get(i);
      JsonNode operationNode = patchNode.get(OPERATION);
      JsonNode pathNode = patchNode.get(PATH);
      JsonNode valueNode = patchNode.get(VALUE);

      List<PatchOperation> nodeOperations = convertNodeToPatchOperations(operationNode.asText(), pathNode.asText(), valueNode);
      if (!nodeOperations.isEmpty()) {
        operations.addAll(nodeOperations);
      }
    }    
    
    return operations;
  }

  private List<PatchOperation> convertNodeToPatchOperations(String operationNode, String diffPath, JsonNode valueNode) throws IllegalArgumentException, IllegalAccessException, JsonProcessingException {
    log.debug("convertNodeToPatchOperations: {} , {}", operationNode, diffPath);
    List<PatchOperation> operations = new ArrayList<>();
    PatchOperation.Type patchOpType = PatchOperation.Type.valueOf(operationNode.toUpperCase(Locale.ROOT));

    if (diffPath != null && diffPath.length() >= 1) {
      ParseData parseData = new ParseData(diffPath);
      
      if (parseData.pathParts.isEmpty()) {
        operations.add(handleExtensions(valueNode, patchOpType, parseData));
      } else {
        operations.addAll(handleAttributes(valueNode, patchOpType, parseData));
      }
    }
    
    return operations;
  }

  private PatchOperation handleExtensions(JsonNode valueNode, Type patchOpType, ParseData parseData) throws JsonProcessingException {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(patchOpType);
    
    AttributeReference attributeReference = new AttributeReference(parseData.pathUri, null);
    PatchOperationPath patchOperationPath = new PatchOperationPath();
    ValuePathExpression valuePathExpression = new ValuePathExpression(attributeReference);
    patchOperationPath.setValuePathExpression(valuePathExpression);
    
    operation.setPath(patchOperationPath);
    operation.setValue(determineValue(patchOpType, valueNode, parseData));
    
    return operation;
  }

  @SuppressWarnings("unchecked")
  private List<PatchOperation> handleAttributes(JsonNode valueNode, PatchOperation.Type patchOpType, ParseData parseData) throws IllegalAccessException, JsonProcessingException {
    log.trace("in handleAttributes");
    List<PatchOperation> operations = new ArrayList<>();
    
    List<String> attributeReferenceList = new ArrayList<>();
    FilterExpression valueFilterExpression = null;
    List<String> subAttributes = new ArrayList<>();

    boolean processingMultiValued = false;
    boolean processedMultiValued = false;
    boolean done = false;
    
    int i = 0;
    for (String pathPart : parseData.pathParts) {
      log.trace("path part: {}", pathPart);
      if (done) {
        throw new RuntimeException("Path should be done... Attribute not supported by the schema: " + pathPart);
      } else if (processingMultiValued) {
        parseData.traverseObjectsInArray(pathPart, patchOpType);

        if (!parseData.isLastIndex(i) || patchOpType != PatchOperation.Type.ADD) {
          if (parseData.originalObject instanceof TypedAttribute) {
            TypedAttribute typedAttribute = (TypedAttribute) parseData.originalObject;
            String type = typedAttribute.getType();
            valueFilterExpression = new AttributeComparisonExpression(new AttributeReference("type"), CompareOperator.EQ, type);
          } else if (parseData.originalObject instanceof String || parseData.originalObject instanceof Number) {
            String toString = parseData.originalObject.toString();
            valueFilterExpression = new AttributeComparisonExpression(new AttributeReference("value"), CompareOperator.EQ, toString);
          } else if(parseData.originalObject instanceof Enum) {
            Enum<?> tempEnum = (Enum<?>)parseData.originalObject;
            valueFilterExpression = new AttributeComparisonExpression(new AttributeReference("value"), CompareOperator.EQ, tempEnum.name());
          } else {
            if (parseData.originalObject != null) {
              log.debug("Attribute: {} doesn't implement TypedAttribute, can't create ValueFilterExpression", parseData.originalObject.getClass());
            } else {
              log.debug("Attribute: null doesn't implement TypedAttribute, can't create ValueFilterExpression");
            }
            valueFilterExpression = new AttributeComparisonExpression(new AttributeReference("value"), CompareOperator.EQ, "?");
          }
          processingMultiValued = false;
          processedMultiValued = true;
        }
      } else {
        Attribute attribute = parseData.ac.getAttribute(pathPart);
        
        if (attribute != null) {
          if (processedMultiValued) {
            subAttributes.add(pathPart);
          } else {
            log.debug("Adding {} to attributeReferenceList", pathPart);
            attributeReferenceList.add(pathPart);
          }
  
          parseData.traverseObjects(pathPart, attribute);
  
          if (patchOpType == Type.REPLACE && 
              (parseData.resourceObject != null && parseData.resourceObject instanceof Collection && !((Collection<?>)parseData.resourceObject).isEmpty()) &&
              (parseData.originalObject == null || 
              (parseData.originalObject instanceof Collection && ((Collection<?>)parseData.originalObject).isEmpty()))) {
            patchOpType = Type.ADD;
          }
          
          if (attribute.isMultiValued()) {
            processingMultiValued = true;
          } else if (attribute.getType() != Attribute.Type.COMPLEX) {
            done = true;
          }
        }
      }
      ++i;
    }
    
    if (patchOpType == Type.REPLACE && (parseData.resourceObject == null || 
        (parseData.resourceObject instanceof Collection && ((Collection<?>)parseData.resourceObject).isEmpty()))) {
      patchOpType = Type.REMOVE;
      valueNode = null;
    }
    
    if (patchOpType == Type.REPLACE && parseData.originalObject == null) {
      patchOpType = Type.ADD;
    }
        
    if (!attributeReferenceList.isEmpty()) {
      Object value = determineValue(patchOpType, valueNode, parseData);
      
      if (value != null && value instanceof ArrayList) {
        List<Object> objList = (List<Object>)value;
        
        if (!objList.isEmpty()) {
          Object firstElement = objList.get(0); 
          if (firstElement instanceof ArrayList) {
            objList = (List<Object>) firstElement;
          }
          
          for (Object obj : objList) {
            PatchOperation operation = buildPatchOperation(patchOpType, parseData, attributeReferenceList, valueFilterExpression, subAttributes, obj);
            if (operation != null) {
              operations.add(operation);
            }
          }
        }
      } else {
        PatchOperation operation = buildPatchOperation(patchOpType, parseData, attributeReferenceList, valueFilterExpression, subAttributes, value);
        if (operation != null) {
          operations.add(operation);
        }
      }
    }
    
    return operations;
  }
  
  private PatchOperation buildPatchOperation(PatchOperation.Type patchOpType, ParseData parseData, List<String> attributeReferenceList,
                                             FilterExpression valueFilterExpression, List<String> subAttributes, Object value) {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(patchOpType);
    String attribute = attributeReferenceList.get(0);
    String subAttribute = attributeReferenceList.size() > 1 ? attributeReferenceList.get(1) : null;

    if (subAttribute == null && !subAttributes.isEmpty()) {
      subAttribute = subAttributes.get(0);
    }
    AttributeReference attributeReference = new AttributeReference(parseData.pathUri, attribute, subAttribute);
    PatchOperationPath patchOperationPath = new PatchOperationPath();
    ValuePathExpression valuePathExpression = new ValuePathExpression(attributeReference, valueFilterExpression);
    patchOperationPath.setValuePathExpression(valuePathExpression);

    operation.setPath(patchOperationPath);
    operation.setValue(value);
    
    return operation;
  }
    
  private Object determineValue(PatchOperation.Type patchOpType, JsonNode valueNode, ParseData parseData) throws JsonProcessingException {
    if (patchOpType == PatchOperation.Type.REMOVE) {
      return null;
    }

    if (valueNode != null) {
      if (valueNode instanceof TextNode) {
        return valueNode.asText();
      } else if (valueNode instanceof BooleanNode) {
        return valueNode.asBoolean();
      } else if (valueNode instanceof DoubleNode || valueNode instanceof FloatNode) {
        return valueNode.asDouble();
      } else if (valueNode instanceof IntNode) {
        return valueNode.asInt();
      } else if (valueNode instanceof NullNode) {
        return null;
      } else if (valueNode instanceof ObjectNode) {
        return parseData.resourceObject;
      } else if (valueNode instanceof POJONode) {
        POJONode pojoNode = (POJONode) valueNode;
        return pojoNode.getPojo();
      } else if (valueNode instanceof ArrayNode) {
        ArrayNode arrayNode = (ArrayNode) valueNode;
        List<Object> objectList = new ArrayList<>();
        for(int i = 0; i < arrayNode.size(); i++) {
          Object subObject = determineValue(patchOpType, arrayNode.get(i), parseData);
          if (subObject != null) {
            objectList.add(subObject);
          }
        }
        return objectList;
      }
    }
    return null;
  }

  private class ParseData {

    List<String> pathParts;
    Object originalObject;
    Object resourceObject;
    AttributeContainer ac;
    String pathUri;

    public ParseData(String diffPath) {
      String path = diffPath.substring(1);
      pathParts = new ArrayList<>(Arrays.asList(path.split("/")));

      // Extract namespace
      pathUri = null;

      String firstPathPart = pathParts.get(0);
      if (firstPathPart.contains(":")) {
        pathUri = firstPathPart;
        pathParts.remove(0);
      }

      if (pathUri != null) {
        ac = schemaRegistry.getSchema(pathUri);
        originalObject = original.getExtension(pathUri);
        resourceObject = resource.getExtension(pathUri);
      } else {
        ac = schema;
        originalObject = original;
        resourceObject = resource;
      }
    }

    public void traverseObjects(String pathPart, Attribute attribute) throws IllegalArgumentException, IllegalAccessException {
      originalObject = lookupAttribute(originalObject, ac, pathPart);
      resourceObject = lookupAttribute(resourceObject, ac, pathPart);
      ac = attribute;
    }

    public void traverseObjectsInArray(String pathPart, Type patchOpType) {
      int index = Integer.parseInt(pathPart);

      Attribute attr = (Attribute) ac;
      
      Integer addRemoveOffset = addRemoveOffsetMap.getOrDefault(attr, 0);
      switch (patchOpType) {
      case ADD:
        addRemoveOffsetMap.put(attr, addRemoveOffset - 1);
        break;
      case REMOVE:
        addRemoveOffsetMap.put(attr, addRemoveOffset + 1);
        break;
      case REPLACE:
      default:
        // Do Nothing
        break;
      }
      
      int newindex = index + addRemoveOffset;
      if (newindex < 0) {
        log.error("Attempting to retrieve a negative index:{} on pathPath: {}", newindex, pathPart);
      }
      
      originalObject = lookupIndexInArray(originalObject, newindex);
      resourceObject = lookupIndexInArray(resourceObject, index);
    }

    public boolean isLastIndex(int index) {
      int numPathParts = pathParts.size();
      return index == (numPathParts - 1);
    }

    @SuppressWarnings("rawtypes")
    private Object lookupIndexInArray(Object object, int index) {
      if (!(object instanceof List)) {
        throw new RuntimeException("Unsupported collection type: " + object.getClass());
      }
      List list = (List) object;
      if (index >= list.size()) {
        return null;
      }

      return list.get(index);
    }

    private Object lookupAttribute(Object object, AttributeContainer ac, String attributeName) throws IllegalArgumentException, IllegalAccessException {
      if (object == null) {
        return null;
      }

      Attribute attribute = ac.getAttribute(attributeName);
      return attribute.getAccessor().get(object);
    }
  }
}
