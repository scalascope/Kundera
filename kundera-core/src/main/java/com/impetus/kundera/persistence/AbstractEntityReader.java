/*******************************************************************************
 * * Copyright 2011 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.kundera.persistence;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.JoinColumn;
import javax.persistence.PersistenceException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.EnhanceEntity;
import com.impetus.kundera.index.DocumentIndexer;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.MetadataUtils;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.JoinTableMetadata;
import com.impetus.kundera.metadata.model.Relation;
import com.impetus.kundera.metadata.model.Relation.ForeignKey;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorHelper;

/**
 * The Class AbstractEntityReader.
 * 
 * @author vivek.mishra
 */
public class AbstractEntityReader
{

    /** The log. */
    private static Log log = LogFactory.getLog(AbstractEntityReader.class);

    /** The lucene query from jpa query. */
    protected String luceneQueryFromJPAQuery;

    /**
     * Recursively fetches child entities for a given entity contained in
     * {@link EnhanceEntity}
     * 
     * @param e
     * @param client
     * @param m
     * @param pd
     * @return
     */
    public Object recursivelyFindEntities(EnhanceEntity e, Client client, EntityMetadata m, PersistenceDelegator pd)
    {
        Map<Object, Object> relationValuesMap = new HashMap<Object, Object>();

        Client childClient = null;
        Class childClass = null;
        EntityMetadata childMetadata = null;

        for (Relation relation : m.getRelations())
        {
            if (relation.isRelatedViaJoinTable())
            {
                populateRelationFromJoinTable(e, childClient, m, pd, relation);
            }
            else
            {
                Relation.ForeignKey multiplicity = relation.getType();
                if (multiplicity.equals(Relation.ForeignKey.ONE_TO_ONE)
                        || multiplicity.equals(Relation.ForeignKey.MANY_TO_ONE))
                {
                    // Swapped relationship
                    String relationName = MetadataUtils.getMappedName(m, relation);
                    Object relationValue = e.getRelations() != null ? e.getRelations().get(relationName) : null;

                    childClass = relation.getTargetEntity();
                    childMetadata = KunderaMetadataManager.getEntityMetadata(childClass);
                    Field relationField = relation.getProperty();
                    if (relationValue != null)
                    {
                        if (!relationValuesMap.containsKey(relationValue + childClass.getName())
                                && relationValue != null)
                        {
                            childClient = pd.getClient(childMetadata);

                            Object child = null;

                            if (relationValue != null)
                            {
                                if (childClass.equals(e.getEntity().getClass()))
                                {
                                    child = childClient.find(childClass, relationValue.toString());
                                }
                                else
                                {
                                    child = pd.find(childClass, relationValue.toString());
                                }
                                child = child != null && child instanceof EnhanceEntity ? ((EnhanceEntity) child)
                                        .getEntity() : child;
                            }

                            relationValuesMap.put(relationValue + childClass.getName(), child);

                        }
                        Field biDirectionalField = getBiDirectionalField(e.getEntity().getClass(), childClass);
                        onBiDirection(pd, e, client, relation, biDirectionalField, relation.getJoinColumnName(), m,
                                relationValuesMap.get(relationValue + childClass.getName()), childMetadata, childClient);
                        // onBiDirection(e, client, g, m,
                        // collectionHolder.get(relationalValue+childClazz.getName()),
                        // childMetadata, childClient);

                        List<Object> collection = new ArrayList<Object>(1);
                        collection.add(relationValuesMap.get(relationValue + childClass.getName()));
                        PropertyAccessorHelper.set(e.getEntity(), relationField, PropertyAccessorHelper
                                .isCollection(relationField.getType()) ? getFieldInstance(collection, relationField)
                                : collection.get(0));
                    }
                }
                else if (multiplicity.equals(Relation.ForeignKey.ONE_TO_MANY))
                {
                    childClass = relation.getTargetEntity();
                    childMetadata = pd.getMetadata(childClass);

                    Field biDirectionalField = getBiDirectionalField(e.getEntity().getClass(), childClass);

                    childClient = pd.getClient(childMetadata);
                    String relationName = biDirectionalField != null ? m.getIdColumn().getName() : MetadataUtils
                            .getMappedName(m, relation);
                    String relationalValue = e.getEntityId();

                    Field f = relation.getProperty();
                    if (relationName != null && relationalValue != null)
                    {
                        if (!relationValuesMap.containsKey(relationalValue + childClass.getName()))
                        {
                            // create a finder and pass metadata, relationName,
                            // relationalValue.
                            List<Object> childs = null;
                            if (childClass.equals(e.getEntity().getClass()))
                            {
                                childs = childClient.findAll(childClass, relationalValue.toString());
                            }
                            else
                            {
                                if (MetadataUtils.useSecondryIndex(childClient.getPersistenceUnit()))
                                {
                                    childs = childClient.findByRelation(relationName, relationalValue, childClass);

                                    // pass this entity id as a value to be
                                    // searched
                                    // for
                                    // for
                                }
                                else
                                {
                                    if (relation.isJoinedByPrimaryKey())
                                    {
                                        childs = new ArrayList();
                                        // childs.add(childClient.find(childClazz,
                                        // childMetadata, e.getEntityId(),
                                        // null));
                                        childs.add(childClass.equals(e.getEntity().getClass()) ? childs.add(childClient
                                                .find(childClass, e.getEntityId())) : pd.find(childClass,
                                                relationalValue.toString()));
                                    }
                                    else
                                    {
                                        // lucene query, where entity class is
                                        // child
                                        // class, parent class is entity's class
                                        // and
                                        // parentid is entity ID!
                                        // that's it!
                                        String query = getQuery(DocumentIndexer.PARENT_ID_CLASS, e.getEntity()
                                                .getClass().getCanonicalName().toLowerCase(),
                                                DocumentIndexer.PARENT_ID_FIELD, e.getEntityId(), childClass
                                                        .getCanonicalName().toLowerCase());
                                        
                                        Map<String, String> results = childClient.getIndexManager().search(query);
                                        Set<String> rsSet = new HashSet<String>(results.values());
                                        // childs = (List<Object>)
                                        // childClient.find(childClazz,
                                        // rsSet.toArray(new String[] {}));

                                        if (childClass.equals(e.getEntity().getClass()))
                                        {
                                            childs = (List<Object>) childClient.findAll(childClass,
                                                    rsSet.toArray(new String[] {}));
                                        }
                                        else
                                        {
                                            // childs = (List<Object>)
                                            // pd.find(childClass, g,
                                            // rsSet.toArray(new
                                            // String[] {}));
                                            childs = (List<Object>) childClient.findAll(childClass,
                                                    rsSet.toArray(new String[] {}));
                                        }

                                    }
                                }
                            }
                            // secondary indexes.
                            // create sql query for hibernate client.
                            // f = g.getProperty();
                            // relationValuesMap.put(relationalValue +
                            // childClass.getName(), childs);
                            if (childs != null && !childs.isEmpty())
                            {
                                List childCol = new ArrayList(childs.size());
                                for (Object child : childs)
                                {
                                    Object o = child instanceof EnhanceEntity ? ((EnhanceEntity) child).getEntity()
                                            : child;
                                    // biDirectionalField =
                                    // getBiDirectionalField(e.getEntity().getClass(),
                                    // childClass);
                                    if (!o.getClass().equals(e.getEntity().getClass()))
                                    {
                                        recursivelyFindEntities(
                                                new EnhanceEntity(o, PropertyAccessorHelper.getId(o, childMetadata),
                                                        null), childClient, childMetadata, pd);
                                    }
                                    onBiDirection(pd, e, client, relation, biDirectionalField,
                                            relation.getJoinColumnName(), m, o, childMetadata, childClient);
                                    childCol.add(o);

                                }
                                relationValuesMap.put(relationalValue + childClass.getName(), childCol);
                            }
                        }
                    }
                    // handle bi direction here.

                    onReflect(e.getEntity(), f, (List) relationValuesMap.get(relationalValue + childClass.getName()));

                }

            }
        }

        return e.getEntity();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.persistence.EntityReader#findById(java.lang.String,
     * com.impetus.kundera.metadata.model.EntityMetadata, java.util.List,
     * com.impetus.kundera.client.Client)
     */

    protected EnhanceEntity findById(Object primaryKey, EntityMetadata m, List<String> relationNames, Client client)
    {
        try
        {
            Object o = client.find(m.getEntityClazz(), primaryKey);

            if (o == null)
            {
                // No entity found
                return null;
            }
            else
            {
                return o instanceof EnhanceEntity ? (EnhanceEntity) o : new EnhanceEntity(o, getId(o, m), null);
            }

        }
        catch (Exception e)
        {
            throw new PersistenceException(e);
        }

    }

    /**
     * Compute graph.
     * 
     * @param e
     *            the e
     * @param graphs
     *            the graphs
     * @param collectionHolder
     *            the collection holder
     * @param client
     *            the client
     * @param m
     *            the m
     * @param persistenceDelegeator
     *            the persistence delegeator
     * @return the object
     * @throws Exception
     *             the exception
     */

    // Delete after refactoring
    // public Object computeGraph(EnhanceEntity e, List<EntitySaveGraph> graphs,
    // Map<Object, Object> collectionHolder,
    // Client client, EntityMetadata m, PersistenceDelegator
    // persistenceDelegeator)
    // {
    //
    // Client childClient = null;
    // Class<?> childClazz = null;
    // EntityMetadata childMetadata = null;
    // // means it is holding associations
    // // read graph for all the association
    // for (EntitySaveGraph g : graphs)
    // {
    // Relation relation = m.getRelation(g.getProperty().getName());
    // if (relation.isRelatedViaJoinTable())
    // {
    // computeJoinTableRelations(e, client, m, g, persistenceDelegeator,
    // relation);
    //
    // }
    // else
    // {
    //
    // if (e.getEntity().getClass().equals(g.getChildClass()))
    // {
    // // Swapped relationship
    // String relationName = g.getfKeyName();
    // Object relationalValue = e.getRelations().get(relationName);
    // childClazz = g.getParentClass();
    // childMetadata = persistenceDelegeator.getMetadata(childClazz);
    //
    // Field f = g.getProperty();
    //
    // if(relationalValue != null) {
    // if (!collectionHolder.containsKey(relationalValue+childClazz.getName()))
    // {
    //
    // childClient = persistenceDelegeator.getClient(childMetadata);
    //
    // // Object child = childClient.find(childClazz,
    // // childMetadata, relationalValue.toString(), null);
    // Object child = null;
    //
    //
    // if (childClazz.equals(e.getEntity().getClass()))
    // {
    // child = childClient.find(childClazz, relationalValue.toString());
    // }
    // else
    // {
    // child = persistenceDelegeator.find(childClazz,
    // relationalValue.toString(), g);
    // }
    //
    //
    //
    // collectionHolder.put(relationalValue+childClazz.getName(), child);
    //
    // // If entity is holding association it means it can not
    // // be a
    // // collection.
    // }
    //
    // onBiDirection(e, client, g, m,
    // collectionHolder.get(relationalValue+childClazz.getName()),
    // childMetadata, childClient);
    //
    // List<Object> collection = new ArrayList<Object>(1);
    // collection.add(collectionHolder.get(relationalValue+childClazz.getName()));
    // PropertyAccessorHelper.set(e.getEntity(), f,
    // PropertyAccessorHelper.isCollection(f.getType()) ?
    // getFieldInstance(collection, f)
    // : collection.get(0));
    // }
    // }
    // else
    // {
    // childClazz = g.getChildClass();
    // childMetadata = persistenceDelegeator.getMetadata(childClazz);
    //
    // childClient = persistenceDelegeator.getClient(childMetadata);
    // String relationName = g.getfKeyName();
    // String relationalValue = e.getEntityId();
    // Field f = g.getProperty();
    // if (!collectionHolder.containsKey(relationalValue+childClazz.getName()))
    // {
    // // create a finder and pass metadata, relationName,
    // // relationalValue.
    // List<Object> childs = null;
    // if (MetadataUtils.useSecondryIndex(childClient.getPersistenceUnit()))
    // {
    // childs = childClient.findByRelation(relationName, relationalValue,
    // childClazz);
    //
    // // pass this entity id as a value to be searched
    // // for
    // // for
    // }
    // else
    // {
    // if (g.isSharedPrimaryKey())
    // {
    // childs = new ArrayList();
    // // childs.add(childClient.find(childClazz,
    // // childMetadata, e.getEntityId(), null));
    // childs.add(childClazz.equals(e.getEntity().getClass()) ?
    // childs.add(childClient.find(
    // childClazz, e.getEntityId())) : persistenceDelegeator
    // .find(childClazz, relationalValue.toString()));
    // }
    // else
    // {
    // // lucene query, where entity class is child
    // // class, parent class is entity's class and
    // // parentid is entity ID!
    // // that's it!
    // String query = getQuery(DocumentIndexer.PARENT_ID_CLASS,
    // e.getEntity().getClass()
    // .getCanonicalName().toLowerCase(), DocumentIndexer.PARENT_ID_FIELD,
    // e.getEntityId(), childClazz.getCanonicalName().toLowerCase());
    // // System.out.println(query);
    // Map<String, String> results =
    // childClient.getIndexManager().search(query);
    // Set<String> rsSet = new HashSet<String>(results.values());
    // // childs = (List<Object>)
    // // childClient.find(childClazz,
    // // rsSet.toArray(new String[] {}));
    //
    // if (childClazz.equals(e.getEntity().getClass()))
    // {
    // childs = (List<Object>) childClient.findAll(childClazz,
    // rsSet.toArray(new String[] {}));
    // }
    // else
    // {
    // childs = (List<Object>) persistenceDelegeator.find(childClazz, g,
    // rsSet.toArray(new String[] {}));
    // }
    //
    // }
    // }
    // // secondary indexes.
    // // create sql query for hibernate client.
    // // f = g.getProperty();
    // collectionHolder.put(relationalValue+childClazz.getName(), childs);
    // if (childs != null)
    // {
    // for (Object child : childs)
    // {
    // onBiDirection(e, client, g, m, child, childMetadata, childClient);
    // }
    // }
    // }
    // // handle bi direction here.
    //
    // onReflect(e.getEntity(), f, (List)
    // collectionHolder.get(relationalValue+childClazz.getName()));
    //
    // }
    //
    // }
    // // this is a single place holder for bi direction.
    // }
    // return e.getEntity();
    // }

    /**
     * On reflect.
     * 
     * @param entity
     *            the entity
     * @param f
     *            the f
     * @param childs
     *            the childs
     * @return the sets the
     * @throws PropertyAccessException
     *             the property access exception
     */
    private Set<?> onReflect(Object entity, Field f, List<?> childs) throws PropertyAccessException
    {
        Set chids = new HashSet();
        if (childs != null)
        {
            chids = new HashSet(childs);
            // TODO: need to store object in sesion.
            // getSession().store(id, entity)
            PropertyAccessorHelper.set(entity, f,
                    PropertyAccessorHelper.isCollection(f.getType()) ? getFieldInstance(childs, f) : childs.get(0));
        }
        return chids;
    }

    /**
     * Gets the field instance.
     * 
     * @param chids
     *            the chids
     * @param f
     *            the f
     * @return the field instance
     */
    private Object getFieldInstance(List chids, Field f)
    {

        if (Set.class.isAssignableFrom(f.getType()))
        {
            Set col = new HashSet(chids);
            return col;
        }
        return chids;
    }

    /**
     * Returns lucene based query.
     * 
     * @param clazzFieldName
     *            lucene field name for class
     * @param clazzName
     *            class name
     * @param idFieldName
     *            lucene id field name
     * @param idFieldValue
     *            lucene id field value
     * @param entityClazz
     *            the entity clazz
     * @return query lucene query.
     */
    protected static String getQuery(String clazzFieldName, String clazzName, String idFieldName, String idFieldValue,
            String entityClazz)
    {
        StringBuffer sb = new StringBuffer("+");
        sb.append(clazzFieldName);
        sb.append(":");
        sb.append(clazzName);
        sb.append(" AND ");
        sb.append("+");
        sb.append(idFieldName);
        sb.append(":");
        sb.append(idFieldValue);
        if (entityClazz != null)
        {
            sb.append(" AND ");
            sb.append("+");
            sb.append(DocumentIndexer.ENTITY_CLASS_FIELD);
            sb.append(":");
            sb.append(entityClazz);
        }
        return sb.toString();
    }

    // /**
    // * On bi direction.
    // *
    // * @param e
    // * the e
    // * @param client
    // * the client
    // * @param objectGraph
    // * the object graph
    // * @param origMetadata
    // * the orig metadata
    // * @param child
    // * the child
    // * @param childMetadata
    // * the child metadata
    // * @param childClient
    // * the child client
    // * @throws Exception
    // * the exception
    // */
    // private void onBiDirection(EnhanceEntity e, Client client,
    // EntitySaveGraph objectGraph,
    // EntityMetadata origMetadata, Object child, EntityMetadata childMetadata,
    // Client childClient)
    // {
    // // Process bidirectional processing only if it's a bidirectional graph
    // // and child exists (there is a possibility that this
    // // child make already have been deleted
    // if (!objectGraph.isUniDirectional() && child != null)
    // {
    // // Add original fetched entity.
    // List obj = new ArrayList();
    //
    // Relation relation =
    // childMetadata.getRelation(objectGraph.getBidirectionalProperty().getName());
    //
    // // join column name is pk of entity and
    // // If relation is One to Many or MANY TO MANY for associated
    // // entity. Require to fetch all associated entity
    //
    // if (relation.getType().equals(ForeignKey.ONE_TO_MANY) ||
    // relation.getType().equals(ForeignKey.MANY_TO_MANY))
    // {
    // String query = null;
    // try
    // {
    // String id = PropertyAccessorHelper.getId(child, childMetadata);
    // List<Object> results = null;
    //
    // if (MetadataUtils.useSecondryIndex(client.getPersistenceUnit()))
    // {
    // if (origMetadata.isRelationViaJoinTable())
    // {
    // Relation joinTableRelation =
    // origMetadata.getRelation(objectGraph.getProperty().getName());
    // JoinTableMetadata jtMetadata = joinTableRelation.getJoinTableMetadata();
    // String joinTableName = jtMetadata.getJoinTableName();
    //
    // Set<String> joinColumns = jtMetadata.getJoinColumns();
    // Set<String> inverseJoinColumns = jtMetadata.getInverseJoinColumns();
    //
    // String joinColumnName = (String) joinColumns.toArray()[0];
    // String inverseJoinColumnName = (String) inverseJoinColumns.toArray()[0];
    //
    // List<Object> parentEntities =
    // client.findParentEntityFromJoinTable(origMetadata,
    // joinTableName, joinColumnName, inverseJoinColumnName, id);
    //
    // if (results == null)
    // {
    // results = new ArrayList<Object>();
    // }
    //
    // if (parentEntities != null)
    // {
    // results.addAll(parentEntities);
    // }
    //
    // }
    // else
    // {
    // results = client.findByRelation(objectGraph.getfKeyName(), id,
    // origMetadata.getEntityClazz());
    // }
    //
    // }
    // else
    // {
    // Map<String, String> keys = null;
    // if (relation.getType().equals(ForeignKey.ONE_TO_MANY))
    // {
    // query = getQuery(DocumentIndexer.PARENT_ID_CLASS,
    // child.getClass().getCanonicalName()
    // .toLowerCase(), DocumentIndexer.PARENT_ID_FIELD, id,
    // e.getEntity().getClass()
    // .getCanonicalName().toLowerCase());
    // // System.out.println(query);
    // keys = client.getIndexManager().search(query);
    // }
    // else
    // {
    // query = getQuery(DocumentIndexer.ENTITY_CLASS_FIELD,
    // child.getClass().getCanonicalName()
    // .toLowerCase(), DocumentIndexer.ENTITY_ID_FIELD, id, null);
    // keys = client.getIndexManager().fetchRelation(query);
    //
    // }
    // Set<String> uqSet = new HashSet<String>(keys.values());
    // results = new ArrayList<Object>();
    // for (String rowKey : uqSet)
    // {
    // Object result = client.find(e.getEntity().getClass(), rowKey);
    // if (result != null)
    // {
    // results.add(result);
    // }
    //
    // }
    // }
    //
    // if (results != null)
    // {
    // obj.addAll(results);
    // }
    // }
    // catch (PropertyAccessException ex)
    // {
    // log.error("Error while fetching data for reverse bidirectional relationship. Details:"
    // + ex.getMessage());
    // throw new
    // EntityReaderException("Error while fetching data for reverse bidirectional relationship",
    // ex);
    // }
    //
    // // In case of other parent object found for given
    // // bidirectional.
    // for (Object o : obj)
    // {
    // if (o != null)
    // {
    // Field f = objectGraph.getProperty();
    //
    // try
    // {
    // if (PropertyAccessorHelper.isCollection(f.getType()))
    // {
    // List l = new ArrayList();
    // l.add(child);
    // Object oo = getFieldInstance(l, f);
    // PropertyAccessorHelper.set(o, f, oo);
    // }
    // else
    // {
    // PropertyAccessorHelper.set(o, f, child);
    // }
    // }
    // catch (PropertyAccessException e1)
    // {
    // log.error("Error while fetching data for reverse bidirectional relationship. Details:"
    // + e1.getMessage());
    // throw new
    // EntityReaderException("Error while fetching data for reverse bidirectional relationship",
    // e1);
    // }
    // }
    //
    // }
    // }
    // else
    // {
    // obj.add(e.getEntity());
    // }
    // try
    // {
    // PropertyAccessorHelper
    // .set(child,
    // objectGraph.getBidirectionalProperty(),
    // PropertyAccessorHelper.isCollection(objectGraph.getBidirectionalProperty().getType())
    // ? getFieldInstance(
    // obj, objectGraph.getBidirectionalProperty()) : e.getEntity());
    // }
    // catch (PropertyAccessException ex)
    // {
    // log.error("Error while fetching data for reverse bidirectional relationship. Details:"
    // + ex.getMessage());
    // throw new
    // EntityReaderException("Error while fetching data for reverse bidirectional relationship",
    // ex);
    // }
    // }
    // }

    /**
     * On bi direction.
     * 
     * @param e
     *            the e
     * @param client
     *            the client
     * @param objectGraph
     *            the object graph
     * @param origMetadata
     *            the orig metadata
     * @param child
     *            the child
     * @param childMetadata
     *            the child metadata
     * @param childClient
     *            the child client
     * @throws Exception
     *             the exception
     */
    private void onBiDirection(PersistenceDelegator pd, EnhanceEntity e, Client client, Relation originalRelation,
            Field bidirectionalField, String relationName, EntityMetadata origMetadata, Object child,
            EntityMetadata childMetadata, Client childClient)
    {
        // Process bidirectional processing only if it's a bidirectional graph
        // and child exists (there is a possibility that this
        // child make already have been deleted
        if (bidirectionalField != null && child != null)
        {
            // Add original fetched entity.
            List obj = new ArrayList();

            Relation relation = childMetadata.getRelation(bidirectionalField.getName());

            // join column name is pk of entity and
            // If relation is One to Many or MANY TO MANY for associated
            // entity. Require to fetch all associated entity

            if (relation.getType().equals(ForeignKey.ONE_TO_MANY) || relation.getType().equals(ForeignKey.MANY_TO_MANY))
            {
                String query = null;
                try
                {
                    String id = PropertyAccessorHelper.getId(child, childMetadata);
                    List<Object> results = null;

                    if (MetadataUtils.useSecondryIndex(client.getPersistenceUnit()))
                    {
                        if (origMetadata.isRelationViaJoinTable())
                        {
                            Relation joinTableRelation = origMetadata.getRelation(originalRelation.getProperty()
                                    .getName());
                            JoinTableMetadata jtMetadata = joinTableRelation.getJoinTableMetadata();
                            String joinTableName = jtMetadata.getJoinTableName();

                            Set<String> joinColumns = jtMetadata.getJoinColumns();
                            Set<String> inverseJoinColumns = jtMetadata.getInverseJoinColumns();

                            String joinColumnName = (String) joinColumns.toArray()[0];
                            String inverseJoinColumnName = (String) inverseJoinColumns.toArray()[0];

                            Object[] pKeys = client.findIdsByColumn(joinTableName, joinColumnName,
                                    inverseJoinColumnName, id, origMetadata.getEntityClazz());

                            // List<Object> parentEntities = pKeys != null ?
                            // client.findAll(origMetadata.getEntityClazz(),
                            // pKeys) : null;

                            for (Object key : pKeys)
                            {
                                String rlName = MetadataUtils.getMappedName(origMetadata, originalRelation);

                                List keys = client.getColumnsById(joinTableName, joinColumnName, inverseJoinColumnName,
                                        key.toString());
                                Object pEntity = client.find(origMetadata.getEntityClazz(), key);
                                pEntity = pEntity != null && pEntity instanceof EnhanceEntity ? ((EnhanceEntity) pEntity)
                                        .getEntity() : pEntity;
                                List recursiveChilds = childClient.findAll(childMetadata.getEntityClazz(),
                                        keys.toArray());
                                if (pEntity != null && recursiveChilds != null && !recursiveChilds.isEmpty())
                                {
                                    PropertyAccessorHelper.set(pEntity, originalRelation.getProperty(),
                                            getFieldInstance(recursiveChilds, originalRelation.getProperty()));
                                    if (results == null)
                                    {
                                        results = new ArrayList<Object>();
                                    }
                                    results.add(pEntity);
                                }
                            }

                            /*
                             * List<Object> parentEntities = null; if(pKeys !=
                             * null) { parentEntities = (List<Object>)
                             * pd.find(origMetadata.getEntityClazz(), pKeys); }
                             */

                            // if (results == null)
                            // {
                            // results = new ArrayList<Object>();
                            // }

                            // if (parentEntities != null)
                            // {
                            // results.addAll(parentEntities);
                            // }

                        }
                        else
                        {
                            results = client.findByRelation(relationName, id, origMetadata.getEntityClazz());
                        }

                    }
                    else
                    {
                        Map<String, String> keys = null;
                        if (relation.getType().equals(ForeignKey.ONE_TO_MANY))
                        {
                            query = getQuery(DocumentIndexer.PARENT_ID_CLASS, child.getClass().getCanonicalName()
                                    .toLowerCase(), DocumentIndexer.PARENT_ID_FIELD, id, e.getEntity().getClass()
                                    .getCanonicalName().toLowerCase());
                            // System.out.println(query);
                            keys = client.getIndexManager().search(query);
                        }
                        else
                        {
                            query = getQuery(DocumentIndexer.ENTITY_CLASS_FIELD, child.getClass().getCanonicalName()
                                    .toLowerCase(), DocumentIndexer.ENTITY_ID_FIELD, id, null);
                            keys = client.getIndexManager().fetchRelation(query);

                        }
                        Set<String> uqSet = new HashSet<String>(keys.values());
                        results = new ArrayList<Object>();
                        for (String rowKey : uqSet)
                        {
                            Object result = client.find(e.getEntity().getClass(), rowKey);
                            if (result != null)
                            {
                                results.add(result instanceof EnhanceEntity ? ((EnhanceEntity) result).getEntity()
                                        : result);
                            }

                        }
                    }

                    if (results != null)
                    {
                        obj.addAll(results);
                    }
                }
                catch (PropertyAccessException ex)
                {
                    log.error("Error while fetching data for reverse bidirectional relationship. Details:"
                            + ex.getMessage());
                    throw new EntityReaderException("Error while fetching data for reverse bidirectional relationship",
                            ex);
                }

                // In case of other parent object found for given
                // bidirectional.
                for (Object o : obj)
                {
                    if (o != null && !relation.getType().equals(ForeignKey.MANY_TO_MANY))
                    {
                        o = o instanceof EnhanceEntity ? ((EnhanceEntity) o).getEntity() : o;
                        Field f = originalRelation.getProperty();

                        try
                        {
                            if (PropertyAccessorHelper.isCollection(f.getType()))
                            {
                                List l = new ArrayList();
                                l.add(child);
                                Object oo = getFieldInstance(l, f);
                                PropertyAccessorHelper.set(o, f, oo);
                            }
                            else
                            {
                                PropertyAccessorHelper.set(o, f, child);
                            }
                        }
                        catch (PropertyAccessException e1)
                        {
                            log.error("Error while fetching data for reverse bidirectional relationship. Details:"
                                    + e1.getMessage());
                            throw new EntityReaderException(
                                    "Error while fetching data for reverse bidirectional relationship", e1);
                        }
                    }

                }
            }
            else
            {
                obj.add(e.getEntity());
            }
            try
            {
                PropertyAccessorHelper.set(
                        child,
                        bidirectionalField,
                        PropertyAccessorHelper.isCollection(bidirectionalField.getType()) ? getFieldInstance(obj,
                                bidirectionalField) : e.getEntity());
            }
            catch (PropertyAccessException ex)
            {
                log.error("Error while fetching data for reverse bidirectional relationship. Details:"
                        + ex.getMessage());
                throw new EntityReaderException("Error while fetching data for reverse bidirectional relationship", ex);
            }
        }
    }

    /**
     * On association using lucene.
     * 
     * @param m
     *            the m
     * @param client
     *            the client
     * @param ls
     *            the ls
     * @return the list
     */
    protected List<EnhanceEntity> onAssociationUsingLucene(EntityMetadata m, Client client, List<EnhanceEntity> ls)
    {
        Set<String> rSet = fetchDataFromLucene(client);
        List resultList = client.findAll(m.getEntityClazz(), rSet.toArray(new String[] {}));
        return m.getRelationNames() != null && !m.getRelationNames().isEmpty() ? resultList : transform(m, ls,
                resultList);
    }

    /**
     * Transform.
     * 
     * @param m
     *            the m
     * @param ls
     *            the ls
     * @param resultList
     *            the result list
     * @return the list
     */
    protected List<EnhanceEntity> transform(EntityMetadata m, List<EnhanceEntity> ls, List resultList)
    {
        if ((ls == null || ls.isEmpty()) && resultList != null && !resultList.isEmpty())
        {
            ls = new ArrayList<EnhanceEntity>(resultList.size());
        }
        for (Object r : resultList)
        {
            EnhanceEntity e = new EnhanceEntity(r, getId(r, m), null);
            ls.add(e);
        }
        return ls;
    }

    /**
     * Fetch data from lucene.
     * 
     * @param client
     *            the client
     * @return the sets the
     */
    protected Set<String> fetchDataFromLucene(Client client)
    {
        // use lucene to query and get Pk's only.
        // go to client and get relation with values.!
        // populate EnhanceEntity
        Map<String, String> results = client.getIndexManager().search(luceneQueryFromJPAQuery);
        Set<String> rSet = new HashSet<String>(results.values());
        return rSet;
    }

    /**
     * Gets the id.
     * 
     * @param entity
     *            the entity
     * @param metadata
     *            the metadata
     * @return the id
     */
    protected String getId(Object entity, EntityMetadata metadata)
    {
        try
        {
            return PropertyAccessorHelper.getId(entity, metadata);
        }
        catch (PropertyAccessException e)
        {
            log.error("Error while Getting ID. Details:" + e.getMessage());
            throw new EntityReaderException("Error while Getting ID for entity " + entity, e);
        }

    }

    private void populateRelationFromJoinTable(EnhanceEntity e, Client client, EntityMetadata entityMetadata,
            PersistenceDelegator delegator, Relation relation)
    {
        Object entity = e.getEntity();

        JoinTableMetadata jtMetadata = relation.getJoinTableMetadata();
        String joinTableName = jtMetadata.getJoinTableName();

        Set<String> joinColumns = jtMetadata.getJoinColumns();
        Set<String> inverseJoinColumns = jtMetadata.getInverseJoinColumns();

        String joinColumnName = (String) joinColumns.toArray()[0];
        String inverseJoinColumnName = (String) inverseJoinColumns.toArray()[0];

        EntityMetadata relMetadata = delegator.getMetadata(relation.getTargetEntity());

        Client pClient = delegator.getClient(entityMetadata);
        List<?> foreignKeys = pClient.getColumnsById(joinTableName, joinColumnName, inverseJoinColumnName,
                e.getEntityId());

        List childrenEntities = new ArrayList();
        for (Object foreignKey : foreignKeys)
        {
            EntityMetadata childMetadata = delegator.getMetadata(relation.getTargetEntity());
            Client childClient = delegator.getClient(childMetadata);

            Object child = delegator.find(relation.getTargetEntity(), foreignKey);

            Object obj = child instanceof EnhanceEntity && child != null ? ((EnhanceEntity) child).getEntity() : child;
            // Handle bidirection
            // onBiDirection(e, client, objectGraph, entityMetadata, child,
            // childMetadata, childClient);
            onBiDirection(delegator, e, pClient, relation,
                    getBiDirectionalField(entity.getClass(), relation.getTargetEntity()), joinColumnName,
                    entityMetadata, obj, childMetadata, childClient);

            childrenEntities.add(obj);
        }

        Field childField = relation.getProperty();

        try
        {
            PropertyAccessorHelper.set(
                    entity,
                    childField,
                    PropertyAccessorHelper.isCollection(childField.getType()) ? getFieldInstance(childrenEntities,
                            childField) : childrenEntities.get(0));
        }
        catch (PropertyAccessException ex)
        {
            throw new EntityReaderException(ex);
        }

    }

    // /**
    // * Compute join table relations.
    // *
    // * @param e
    // * the e
    // * @param client
    // * the client
    // * @param entityMetadata
    // * the entity metadata
    // * @param objectGraph
    // * the object graph
    // * @param delegator
    // * the delegator
    // * @param relation
    // * the relation
    // */
    // @Deprecated
    // private void computeJoinTableRelations(EnhanceEntity e, Client client,
    // EntityMetadata entityMetadata,
    // EntitySaveGraph objectGraph, PersistenceDelegator delegator, Relation
    // relation)
    // {
    //
    // Object entity = e.getEntity();
    //
    // objectGraph.setParentId(getId(entity, entityMetadata));
    // JoinTableMetadata jtMetadata = relation.getJoinTableMetadata();
    // String joinTableName = jtMetadata.getJoinTableName();
    //
    // Set<String> joinColumns = jtMetadata.getJoinColumns();
    // Set<String> inverseJoinColumns = jtMetadata.getInverseJoinColumns();
    //
    // String joinColumnName = (String) joinColumns.toArray()[0];
    // String inverseJoinColumnName = (String) inverseJoinColumns.toArray()[0];
    //
    // EntityMetadata relMetadata =
    // delegator.getMetadata(objectGraph.getChildClass());
    //
    // Client pClient = delegator.getClient(entityMetadata);
    // List<?> foreignKeys = pClient.getForeignKeysFromJoinTable(joinTableName,
    // joinColumnName, inverseJoinColumnName,
    // relMetadata, objectGraph.getParentId());
    //
    // List childrenEntities = new ArrayList();
    // for (Object foreignKey : foreignKeys)
    // {
    // EntityMetadata childMetadata =
    // delegator.getMetadata(relation.getTargetEntity());
    // Client childClient = delegator.getClient(childMetadata);
    // // Object child = childClient.find(relation.getTargetEntity(),
    // // childMetadata, (String) foreignKey, null);
    // Object child = delegator.find(relation.getTargetEntity(), foreignKey);
    //
    // onBiDirection(e, client, objectGraph, entityMetadata, child,
    // childMetadata, childClient);
    //
    // childrenEntities.add(child);
    // }
    //
    // Field childField = objectGraph.getProperty();
    //
    // try
    // {
    // PropertyAccessorHelper.set(
    // entity,
    // childField,
    // PropertyAccessorHelper.isCollection(childField.getType()) ?
    // getFieldInstance(childrenEntities,
    // childField) : childrenEntities.get(0));
    // }
    // catch (PropertyAccessException ex)
    // {
    // throw new EntityReaderException(ex);
    // }
    //
    // }

    /**
     * Returns associated bi-directional field.
     * 
     * @param originalClazz
     *            Original class
     * @param referencedClass
     *            Referenced class.
     */
    private Field getBiDirectionalField(Class originalClazz, Class referencedClass)
    {
        Field[] fields = referencedClass.getDeclaredFields();
        Class<?> clazzz = null;
        Field biDirectionalField = null;
        for (Field field : fields)
        {
            clazzz = field.getType();
            if (PropertyAccessorHelper.isCollection(clazzz))
            {
                ParameterizedType type = (ParameterizedType) field.getGenericType();
                Type[] types = type.getActualTypeArguments();
                clazzz = (Class<?>) types[0];
            }
            if (clazzz.equals(originalClazz))
            {
                biDirectionalField = field;
                break;
            }
        }

        return biDirectionalField;
    }

    /**
     * Gets the relation field name.
     * 
     * @param relation
     *            the relation
     * @return the relation field name
     */
    protected String getJoinColumnName(Field relation)
    {
        String columnName = null;
        JoinColumn ann = relation.getAnnotation(JoinColumn.class);
        if (ann != null)
        {
            columnName = ann.name();

        }
        return columnName != null ? columnName : relation.getName();
    }

}
