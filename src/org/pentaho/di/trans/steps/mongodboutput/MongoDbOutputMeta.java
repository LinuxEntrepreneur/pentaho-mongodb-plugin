/*******************************************************************************
 *
 * Pentaho Big Data
 *
 * Copyright (C) 2002-2012 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.trans.steps.mongodboutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.CheckResult;
import org.pentaho.di.core.CheckResultInterface;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.Counter;
import org.pentaho.di.core.annotations.Step;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.encryption.Encr;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleXMLException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.repository.ObjectId;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStepMeta;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.w3c.dom.Node;

/**
 * Class providing an output step for writing data to a MongoDB collection.
 * Supports insert, truncate, upsert, multi-update (update all matching docs)
 * and modifier update (update only certain fields) operations. Can also create
 * and drop indexes based on one or more fields.
 * 
 * @author Mark Hall (mhall{[at]}pentaho{[dot]}com)
 */
@Step(id = "MongoDbOutput", image = "MongoDB.png", name = "MongoDB Output", description = "Writes to a Mongo DB collection", categoryDescription = "Big Data")
public class MongoDbOutputMeta extends BaseStepMeta implements
    StepMetaInterface {

  private static Class<?> PKG = MongoDbOutputMeta.class; // for i18n purposes

  /**
   * Class encapsulating paths to document fields
   * 
   * @author Mark Hall (mhall{[at]}pentaho{[dot]}com)
   */
  public static class MongoField {

    /** Incoming Kettle field name */
    public String m_incomingFieldName = ""; //$NON-NLS-1$

    /**
     * Dot separated path to the corresponding mongo field
     */
    public String m_mongoDocPath = ""; //$NON-NLS-1$

    protected List<String> m_pathList;
    protected List<String> m_tempPathList;

    /**
     * Whether to use the incoming field name as the mongo field key name. If
     * false then the user must supply the terminating field/key name.
     */
    public boolean m_useIncomingFieldNameAsMongoFieldName;

    /** Whether this field is used in the query for an update operation */
    public boolean m_updateMatchField;

    /**
     * Ignored if not doing a modifier update since all mongo paths are involved
     * in a standard upsert. If null/empty then this field is not being updated
     * in the modifier update case.
     * 
     * $set $inc $push - append value to array (or set to [value] if field
     * doesn't exist)
     * 
     * (support any others?)
     */
    public String m_modifierUpdateOperation = "N/A"; //$NON-NLS-1$

    /**
     * If a modifier opp, whether to apply on insert, update or both. Insert or
     * update require knowing whether matching record(s) exist, so require the
     * overhead of a find().limit(1) query for each row. The motivation for this
     * is to allow a single document's structure to be created and developed
     * over multiple kettle rows. E.g. say a document is to contain an array of
     * records where each record in the array itself has a field with is an
     * array. The $push operator specifies the terminal array to add an element
     * to via the dot notation. This terminal array will be created if it does
     * not already exist in the target document; however, it will not create the
     * intermediate array (i.e. the first array in the path). To do this
     * requires a $set operation that is executed only on insert (i.e. if the
     * target document does not exist). Whereas the $push operation should occur
     * only on updates. A single operation can't combine these two as it will
     * result in a conflict (since they operate on the same array).
     */
    public String m_modifierOperationApplyPolicy = "Insert&Update"; //$NON-NLS-1$

    /**
     * If true, then the incoming Kettle field value for this mongo field is
     * expected to be of type String and hold a Mongo doc fragment in JSON
     * format. The doc fragment will be converted into BSON and added into the
     * overall document at the point specified by this MongoField's path
     */
    public boolean m_JSON = false;

    public MongoField copy() {
      MongoField newF = new MongoField();
      newF.m_incomingFieldName = m_incomingFieldName;
      newF.m_mongoDocPath = m_mongoDocPath;
      newF.m_useIncomingFieldNameAsMongoFieldName = m_useIncomingFieldNameAsMongoFieldName;
      newF.m_updateMatchField = m_updateMatchField;
      newF.m_modifierUpdateOperation = m_modifierUpdateOperation;
      newF.m_modifierOperationApplyPolicy = m_modifierOperationApplyPolicy;
      newF.m_JSON = m_JSON;

      return newF;
    }

    public void init(VariableSpace vars) {
      String path = vars.environmentSubstitute(m_mongoDocPath);
      m_pathList = new ArrayList<String>();

      if (!Const.isEmpty(path)) {
        String[] parts = path.split("\\."); //$NON-NLS-1$
        for (String p : parts) {
          m_pathList.add(p);
        }
      }
      m_tempPathList = new ArrayList<String>(m_pathList);
    }

    public void reset() {
      if (m_tempPathList != null && m_tempPathList.size() > 0) {
        m_tempPathList.clear();
      }

      if (m_tempPathList != null) {
        m_tempPathList.addAll(m_pathList);
      }
    }
  }

  /**
   * Class encapsulating index definitions
   * 
   * @author Mark Hall (mhall{[at]}pentaho{[dot]}com)
   */
  public static class MongoIndex {

    /**
     * Dot notation for accessing a fields - e.g. person.address.street. Can
     * also specify entire embedded documents as an index (rather than a
     * primitive key) - e.g. person.address.
     * 
     * Multiple fields are comma-separated followed by an optional "direction"
     * indicator for the index (1 or -1). If omitted, direction is assumed to be
     * 1.
     */
    public String m_pathToFields = ""; //$NON-NLS-1$

    /** whether to drop this index - default is create */
    public boolean m_drop;

    // other options unique, sparse
    public boolean m_unique;
    public boolean m_sparse;

    @Override
    public String toString() {
      StringBuffer buff = new StringBuffer();
      buff.append(m_pathToFields + " (unique = " //$NON-NLS-1$
          + new Boolean(m_unique).toString() + " sparse = " //$NON-NLS-1$
          + new Boolean(m_sparse).toString() + ")"); //$NON-NLS-1$

      return buff.toString();
    }
  }

  /** Hostname/IP address(es) of mongo server(s) */
  protected String m_hostnames = "localhost"; //$NON-NLS-1$

  /** Port that mongo is listening on */
  protected String m_port = "27017"; //$NON-NLS-1$

  /** The DB to use */
  protected String m_dbName;

  /** The collection to use */
  protected String m_collection;

  /** Whether to truncate the collection */
  protected boolean m_truncate;

  /** Username for authentication */
  protected String m_username;

  /** Password for authentication */
  protected String m_password;

  /** True if upserts are to be performed */
  protected boolean m_upsert;

  /**
   * whether to update all records that match during an upsert or just the first
   */
  protected boolean m_multi;

  /**
   * Modifier update involves updating only some fields and is efficient because
   * of low network overhead. Is also particularly efficient for $incr
   * operations since the queried object does not have to be returned in order
   * to increment the field and then saved again.
   * 
   * If modifier update is false, then the standard update/insert operation is
   * performed which involves replacing the matched object with a new object
   * involving all the user-defined mongo paths
   */
  protected boolean m_modifierUpdate;

  /** The batch size for inserts */
  protected String m_batchInsertSize = "100"; //$NON-NLS-1$

  /** The list of paths to document fields for incoming kettle values */
  protected List<MongoField> m_mongoFields;

  /** The list of index definitions (if any) */
  protected List<MongoIndex> m_mongoIndexes;

  /** timeout for the connection */
  protected String m_connectTimeout = ""; // default - never time out //$NON-NLS-1$

  /** timeout on the socket */
  protected String m_socketTimeout = ""; // default - never time out //$NON-NLS-1$

  /** primary, primaryPreferred, secondary, secondaryPreferred, nearest */
  protected String m_readPreference = "primary"; //$NON-NLS-1$

  /**
   * default = 1 (standalone or primary acknowledges writes; -1 no
   * acknowledgement and all errors suppressed; 0 no acknowledgement, but
   * socket/network errors passed to client; "majority" returns after a majority
   * of the replica set members have acknowledged; n (>1) returns after n
   * replica set members have acknowledged; tags (string) specific replica set
   * members with the tags need to acknowledge
   */
  protected String m_writeConcern = ""; //$NON-NLS-1$

  /**
   * The time in milliseconds to wait for replication to succeed, as specified
   * in the w option, before timing out
   */
  protected String m_wTimeout = ""; //$NON-NLS-1$

  /**
   * whether write operations will wait till the mongod acknowledges the write
   * operations and commits the data to the on disk journal
   */
  protected boolean m_journal;

  /**
   * whether to discover and use all replica set members (if not already
   * specified in the hosts field)
   */
  private boolean m_useAllReplicaSetMembers;

  public static final int RETRIES = 5;
  public static final int RETRY_DELAY = 10; // seconds

  private String m_writeRetries = "" + RETRIES; //$NON-NLS-1$
  private String m_writeRetryDelay = "" + RETRY_DELAY; // seconds //$NON-NLS-1$

  @Override
  public void setDefault() {
    m_hostnames = "localhost"; //$NON-NLS-1$
    m_port = "27017"; //$NON-NLS-1$
    m_collection = ""; //$NON-NLS-1$
    m_dbName = ""; //$NON-NLS-1$
    m_upsert = false;
    m_modifierUpdate = false;
    m_truncate = false;
    m_batchInsertSize = "100"; //$NON-NLS-1$
  }

  /**
   * Set the list of document paths
   * 
   * @param mongoFields the list of document paths
   */
  public void setMongoFields(List<MongoField> mongoFields) {
    m_mongoFields = mongoFields;
  }

  /**
   * Get the list of document paths
   * 
   * @return the list of document paths
   */
  public List<MongoField> getMongoFields() {
    return m_mongoFields;
  }

  /**
   * Set the list of document indexes for creation/dropping
   * 
   * @param mongoIndexes the list of indexes
   */
  public void setMongoIndexes(List<MongoIndex> mongoIndexes) {
    m_mongoIndexes = mongoIndexes;
  }

  /**
   * Get the list of document indexes for creation/dropping
   * 
   * @return the list of indexes
   */
  public List<MongoIndex> getMongoIndexes() {
    return m_mongoIndexes;
  }

  /**
   * Set the hostname(s)
   * 
   * @param hosts hostnames (comma separated: host:<port>)
   */
  public void setHostnames(String hosts) {
    m_hostnames = hosts;
  }

  /**
   * Get the hostname(s)
   * 
   * @return the hostnames (comma separated: host:<port>)
   */
  public String getHostnames() {
    return m_hostnames;
  }

  /**
   * @param port the port. This is a port to use for all hostnames (avoids
   *          having to specify the same port for each hostname in the hostnames
   *          list
   */
  public void setPort(String port) {
    m_port = port;
  }

  /**
   * @return the port. This is a port to use for all hostnames (avoids having to
   *         specify the same port for each hostname in the hostnames list
   */
  public String getPort() {
    return m_port;
  }

  /**
   * Set whether to query specified host(s) to discover all replica set member
   * addresses
   * 
   * @param u true if replica set members are to be automatically discovered.
   */
  public void setUseAllReplicaSetMembers(boolean u) {
    m_useAllReplicaSetMembers = u;
  }

  /**
   * Get whether to query specified host(s) to discover all replica set member
   * addresses
   * 
   * @return true if replica set members are to be automatically discovered.
   */
  public boolean getUseAllReplicaSetMembers() {
    return m_useAllReplicaSetMembers;
  }

  /**
   * Set the number of retry attempts to make if a particular write operation
   * fails
   * 
   * @param r the number of retry attempts to make
   */
  public void setWriteRetries(String r) {
    m_writeRetries = r;
  }

  /**
   * Get the number of retry attempts to make if a particular write operation
   * fails
   * 
   * @return the number of retry attempts to make
   */
  public String getWriteRetries() {
    return m_writeRetries;
  }

  /**
   * Set the delay (in seconds) between write retry attempts
   * 
   * @param d the delay in seconds between retry attempts
   */
  public void setWriteRetryDelay(String d) {
    m_writeRetryDelay = d;
  }

  /**
   * Get the delay (in seconds) between write retry attempts
   * 
   * @return the delay in seconds between retry attempts
   */
  public String getWriteRetryDelay() {
    return m_writeRetryDelay;
  }

  /**
   * Set the database name to use
   * 
   * @param db the database name to use
   */
  public void setDBName(String db) {
    m_dbName = db;
  }

  /**
   * Get the database name to use
   * 
   * @return the database name to use
   */
  public String getDBName() {
    return m_dbName;
  }

  /**
   * Set the collection to use
   * 
   * @param collection the collection to use
   */
  public void setCollection(String collection) {
    m_collection = collection;
  }

  /**
   * Get the collection to use
   * 
   * @return the collection to use
   */
  public String getCollection() {
    return m_collection;
  }

  /**
   * Set the username to authenticate with
   * 
   * @param username the username to authenticate with
   */
  public void setUsername(String username) {
    m_username = username;
  }

  /**
   * Get the username to authenticate with
   * 
   * @return the username to authenticate with
   */
  public String getUsername() {
    return m_username;
  }

  /**
   * Set the password to authenticate with
   * 
   * @param password the password to authenticate with
   */
  public void setPassword(String password) {
    m_password = password;
  }

  /**
   * Get the password to authenticate with
   * 
   * @return the password to authenticate with
   */
  public String getPassword() {
    return m_password;
  }

  /**
   * Set whether to upsert rather than insert
   * 
   * @param upsert true if we'll upsert rather than insert
   */
  public void setUpsert(boolean upsert) {
    m_upsert = upsert;
  }

  /**
   * Get whether to upsert rather than insert
   * 
   * @return true if we'll upsert rather than insert
   */
  public boolean getUpsert() {
    return m_upsert;
  }

  /**
   * Set whether the upsert should update all matching records rather than just
   * the first.
   * 
   * @param multi true if all matching records get updated when each row is
   *          upserted
   */
  public void setMulti(boolean multi) {
    m_multi = multi;
  }

  /**
   * Get whether the upsert should update all matching records rather than just
   * the first.
   * 
   * @return true if all matching records get updated when each row is upserted
   */
  public boolean getMulti() {
    return m_multi;
  }

  /**
   * Set whether the upsert operation is a modifier update - i.e where only
   * specified fields in each document get modified rather than a whole document
   * replace.
   * 
   * @param u true if the upsert operation is to be a modifier update
   */
  public void setModifierUpdate(boolean u) {
    m_modifierUpdate = u;
  }

  /**
   * Get whether the upsert operation is a modifier update - i.e where only
   * specified fields in each document get modified rather than a whole document
   * replace.
   * 
   * @return true if the upsert operation is to be a modifier update
   */
  public boolean getModifierUpdate() {
    return m_modifierUpdate;
  }

  /**
   * Set whether to truncate the collection before inserting
   * 
   * @param truncate true if the all records in the collection are to be deleted
   */
  public void setTruncate(boolean truncate) {
    m_truncate = truncate;
  }

  /**
   * Get whether to truncate the collection before inserting
   * 
   * @return true if the all records in the collection are to be deleted
   */
  public boolean getTruncate() {
    return m_truncate;
  }

  /**
   * Get the batch insert size
   * 
   * @return the batch insert size
   */
  public String getBatchInsertSize() {
    return m_batchInsertSize;
  }

  /**
   * Set the batch insert size
   * 
   * @param size the batch insert size
   */
  public void setBatchInsertSize(String size) {
    m_batchInsertSize = size;
  }

  /**
   * Set the connection timeout. The default is never timeout
   * 
   * @param to the connection timeout in milliseconds
   */
  public void setConnectTimeout(String to) {
    m_connectTimeout = to;
  }

  /**
   * Get the connection timeout. The default is never timeout
   * 
   * @return the connection timeout in milliseconds
   */
  public String getConnectTimeout() {
    return m_connectTimeout;
  }

  /**
   * Set the number of milliseconds to attempt a send or receive on a socket
   * before timing out.
   * 
   * @param so the number of milliseconds before socket timeout
   */
  public void setSocketTimeout(String so) {
    m_socketTimeout = so;
  }

  /**
   * Get the number of milliseconds to attempt a send or receive on a socket
   * before timing out.
   * 
   * @return the number of milliseconds before socket timeout
   */
  public String getSocketTimeout() {
    return m_socketTimeout;
  }

  /**
   * Set the read preference to use - primary, primaryPreferred, secondary,
   * secondaryPreferred or nearest.
   * 
   * @param preference the read preference to use
   */
  public void setReadPreference(String preference) {
    m_readPreference = preference;
  }

  /**
   * Get the read preference to use - primary, primaryPreferred, secondary,
   * secondaryPreferred or nearest.
   * 
   * @return the read preference to use
   */
  public String getReadPreference() {
    return m_readPreference;
  }

  /**
   * Set the write concern to use
   * 
   * @param concern the write concern to use
   */
  public void setWriteConcern(String concern) {
    m_writeConcern = concern;
  }

  /**
   * Get the write concern to use
   * 
   * @param co the write concern to use
   */
  public String getWriteConcern() {
    return m_writeConcern;
  }

  /**
   * Set the time in milliseconds to wait for replication to succeed, as
   * specified in the w option, before timing out
   * 
   * @param w the timeout to use
   */
  public void setWTimeout(String w) {
    m_wTimeout = w;
  }

  /**
   * Get the time in milliseconds to wait for replication to succeed, as
   * specified in the w option, before timing out
   * 
   * @return the timeout to use
   */
  public String getWTimeout() {
    return m_wTimeout;
  }

  /**
   * Set whether to use journaled writes
   * 
   * @param j true for journaled writes
   */
  public void setJournal(boolean j) {
    m_journal = j;
  }

  /**
   * Get whether to use journaled writes
   * 
   * @return true for journaled writes
   */
  public boolean getJournal() {
    return m_journal;
  }

  @Override
  public void check(List<CheckResultInterface> remarks, TransMeta transMeta,
      StepMeta stepMeta, RowMetaInterface prev, String[] input,
      String[] output, RowMetaInterface info) {

    CheckResult cr;

    if ((prev == null) || (prev.size() == 0)) {
      cr = new CheckResult(
          CheckResult.TYPE_RESULT_WARNING,
          BaseMessages
              .getString(PKG,
                  "MongoDbOutput.Messages.Error.NotReceivingFieldsFromPreviousSteps"), //$NON-NLS-1$
          stepMeta);
      remarks.add(cr);
    } else {
      cr = new CheckResult(CheckResult.TYPE_RESULT_OK, BaseMessages.getString(
          PKG, "MongoDbOutput.Messages.ReceivingFields", prev.size()), stepMeta); //$NON-NLS-1$
      remarks.add(cr);
    }

    // See if we have input streams leading to this step!
    if (input.length > 0) {
      cr = new CheckResult(CheckResult.TYPE_RESULT_OK, BaseMessages.getString(
          PKG, "MongoDbOutput.Messages.ReceivingInfo"), stepMeta); //$NON-NLS-1$
      remarks.add(cr);
    } else {
      cr = new CheckResult(CheckResult.TYPE_RESULT_ERROR,
          BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Error.NoInputReceivedFromOtherSteps"), //$NON-NLS-1$
          stepMeta);
      remarks.add(cr);
    }

  }

  @Override
  public StepInterface getStep(StepMeta stepMeta,
      StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
      Trans trans) {

    return new MongoDbOutput(stepMeta, stepDataInterface, copyNr, transMeta,
        trans);
  }

  @Override
  public StepDataInterface getStepData() {
    return new MongoDbOutputData();
  }

  @Override
  public String getXML() {
    StringBuffer retval = new StringBuffer();

    if (!Const.isEmpty(m_hostnames)) {
      retval.append("\n    ").append( //$NON-NLS-1$
          XMLHandler.addTagValue("mongo_host", m_hostnames)); //$NON-NLS-1$
    }
    if (!Const.isEmpty(m_port)) {
      retval.append("\n    ").append( //$NON-NLS-1$
          XMLHandler.addTagValue("mongo_port", m_port)); //$NON-NLS-1$
    }

    retval
        .append("    ").append(XMLHandler.addTagValue("use_all_replica_members", m_useAllReplicaSetMembers)); //$NON-NLS-1$ //$NON-NLS-2$

    if (!Const.isEmpty(m_username)) {
      retval.append("\n    ").append( //$NON-NLS-1$
          XMLHandler.addTagValue("mongo_user", m_username)); //$NON-NLS-1$
    }
    if (!Const.isEmpty(m_password)) {
      retval.append("\n    ").append( //$NON-NLS-1$
          XMLHandler.addTagValue("mongo_password", //$NON-NLS-1$
              Encr.encryptPasswordIfNotUsingVariables(m_password)));
    }
    if (!Const.isEmpty(m_dbName)) {
      retval.append("\n    ").append( //$NON-NLS-1$
          XMLHandler.addTagValue("mongo_db", m_dbName)); //$NON-NLS-1$
    }
    if (!Const.isEmpty(m_collection)) {
      retval.append("\n    ").append( //$NON-NLS-1$
          XMLHandler.addTagValue("mongo_collection", m_collection)); //$NON-NLS-1$
    }
    if (!Const.isEmpty(m_batchInsertSize)) {
      retval.append("\n    ").append( //$NON-NLS-1$
          XMLHandler.addTagValue("batch_insert_size", m_batchInsertSize)); //$NON-NLS-1$
    }

    retval.append("    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("connect_timeout", m_connectTimeout)); //$NON-NLS-1$
    retval.append("    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("socket_timeout", m_socketTimeout)); //$NON-NLS-1$
    retval.append("    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("read_preference", m_readPreference)); //$NON-NLS-1$
    retval.append("    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("write_concern", m_writeConcern)); //$NON-NLS-1$
    retval.append("    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("w_timeout", m_wTimeout)); //$NON-NLS-1$
    retval.append("    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("journaled_writes", m_journal)); //$NON-NLS-1$

    retval.append("\n    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("truncate", m_truncate)); //$NON-NLS-1$
    retval.append("\n    ").append(XMLHandler.addTagValue("upsert", m_upsert)); //$NON-NLS-1$ //$NON-NLS-2$
    retval.append("\n    ").append(XMLHandler.addTagValue("multi", m_multi)); //$NON-NLS-1$ //$NON-NLS-2$
    retval.append("\n    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("modifier_update", m_modifierUpdate)); //$NON-NLS-1$

    retval.append("    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("write_retries", m_writeRetries)); //$NON-NLS-1$
    retval.append("    ").append( //$NON-NLS-1$
        XMLHandler.addTagValue("write_retry_delay", m_writeRetryDelay)); //$NON-NLS-1$

    if (m_mongoFields != null && m_mongoFields.size() > 0) {
      retval.append("\n    ").append(XMLHandler.openTag("mongo_fields")); //$NON-NLS-1$ //$NON-NLS-2$

      for (MongoField field : m_mongoFields) {
        retval.append("\n      ").append(XMLHandler.openTag("mongo_field")); //$NON-NLS-1$ //$NON-NLS-2$

        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("incoming_field_name", //$NON-NLS-1$
                field.m_incomingFieldName));
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("mongo_doc_path", field.m_mongoDocPath)); //$NON-NLS-1$
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue(
                "use_incoming_field_name_as_mongo_field_name", //$NON-NLS-1$
                field.m_useIncomingFieldNameAsMongoFieldName));
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("update_match_field", //$NON-NLS-1$
                field.m_updateMatchField));
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("modifier_update_operation", //$NON-NLS-1$
                field.m_modifierUpdateOperation));
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("modifier_policy", //$NON-NLS-1$
                field.m_modifierOperationApplyPolicy));
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("json_field", field.m_JSON)); //$NON-NLS-1$

        retval.append("\n      ").append(XMLHandler.closeTag("mongo_field")); //$NON-NLS-1$ //$NON-NLS-2$
      }

      retval.append("\n    ").append(XMLHandler.closeTag("mongo_fields")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    if (m_mongoIndexes != null && m_mongoIndexes.size() > 0) {
      retval.append("\n    ").append(XMLHandler.openTag("mongo_indexes")); //$NON-NLS-1$ //$NON-NLS-2$

      for (MongoIndex index : m_mongoIndexes) {
        retval.append("\n      ").append(XMLHandler.openTag("mongo_index")); //$NON-NLS-1$ //$NON-NLS-2$

        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("path_to_fields", index.m_pathToFields)); //$NON-NLS-1$
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("drop", index.m_drop)); //$NON-NLS-1$
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("unique", index.m_unique)); //$NON-NLS-1$
        retval.append("\n         ").append( //$NON-NLS-1$
            XMLHandler.addTagValue("sparse", index.m_sparse)); //$NON-NLS-1$

        retval.append("\n      ").append(XMLHandler.closeTag("mongo_index")); //$NON-NLS-1$ //$NON-NLS-2$
      }

      retval.append("\n    ").append(XMLHandler.closeTag("mongo_indexes")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    return retval.toString();
  }

  @Override
  public void loadXML(Node stepnode, List<DatabaseMeta> databases,
      Map<String, Counter> counters) throws KettleXMLException {

    m_hostnames = XMLHandler.getTagValue(stepnode, "mongo_host"); //$NON-NLS-1$
    m_port = XMLHandler.getTagValue(stepnode, "mongo_port"); //$NON-NLS-1$
    m_username = XMLHandler.getTagValue(stepnode, "mongo_user"); //$NON-NLS-1$
    m_password = XMLHandler.getTagValue(stepnode, "mongo_password"); //$NON-NLS-1$
    m_dbName = XMLHandler.getTagValue(stepnode, "mongo_db"); //$NON-NLS-1$
    m_collection = XMLHandler.getTagValue(stepnode, "mongo_collection"); //$NON-NLS-1$
    m_batchInsertSize = XMLHandler.getTagValue(stepnode, "batch_insert_size"); //$NON-NLS-1$

    m_connectTimeout = XMLHandler.getTagValue(stepnode, "connect_timeout"); //$NON-NLS-1$
    m_socketTimeout = XMLHandler.getTagValue(stepnode, "socket_timeout"); //$NON-NLS-1$
    m_readPreference = XMLHandler.getTagValue(stepnode, "read_preference"); //$NON-NLS-1$
    m_writeConcern = XMLHandler.getTagValue(stepnode, "write_concern"); //$NON-NLS-1$
    m_wTimeout = XMLHandler.getTagValue(stepnode, "w_timeout"); //$NON-NLS-1$
    String journaled = XMLHandler.getTagValue(stepnode, "journaled_writes"); //$NON-NLS-1$
    if (!Const.isEmpty(journaled)) {
      m_journal = journaled.equalsIgnoreCase("Y"); //$NON-NLS-1$
    }

    m_truncate = XMLHandler.getTagValue(stepnode, "truncate").equalsIgnoreCase( //$NON-NLS-1$
        "Y"); //$NON-NLS-1$
    m_upsert = XMLHandler.getTagValue(stepnode, "upsert").equalsIgnoreCase("Y"); //$NON-NLS-1$ //$NON-NLS-2$
    m_multi = XMLHandler.getTagValue(stepnode, "multi").equalsIgnoreCase("Y"); //$NON-NLS-1$ //$NON-NLS-2$
    m_modifierUpdate = XMLHandler.getTagValue(stepnode, "modifier_update") //$NON-NLS-1$
        .equalsIgnoreCase("Y"); //$NON-NLS-1$

    m_useAllReplicaSetMembers = false; // default to false for backwards
    // compatibility
    String useAll = XMLHandler.getTagValue(stepnode, "use_all_replica_members"); //$NON-NLS-1$
    if (!Const.isEmpty(useAll)) {
      m_useAllReplicaSetMembers = useAll.equalsIgnoreCase("Y"); //$NON-NLS-1$
    }

    String writeRetries = XMLHandler.getTagValue(stepnode, "write_retries"); //$NON-NLS-1$
    if (!Const.isEmpty(writeRetries)) {
      m_writeRetries = writeRetries;
    }
    String writeRetryDelay = XMLHandler.getTagValue(stepnode,
        "write_retry_delay"); //$NON-NLS-1$
    if (!Const.isEmpty(writeRetryDelay)) {
      m_writeRetryDelay = writeRetryDelay;
    }

    Node fields = XMLHandler.getSubNode(stepnode, "mongo_fields"); //$NON-NLS-1$
    if (fields != null && XMLHandler.countNodes(fields, "mongo_field") > 0) { //$NON-NLS-1$
      int nrfields = XMLHandler.countNodes(fields, "mongo_field"); //$NON-NLS-1$
      m_mongoFields = new ArrayList<MongoField>();

      for (int i = 0; i < nrfields; i++) {
        Node fieldNode = XMLHandler.getSubNodeByNr(fields, "mongo_field", i); //$NON-NLS-1$

        MongoField newField = new MongoField();
        newField.m_incomingFieldName = XMLHandler.getTagValue(fieldNode,
            "incoming_field_name"); //$NON-NLS-1$
        newField.m_mongoDocPath = XMLHandler.getTagValue(fieldNode,
            "mongo_doc_path"); //$NON-NLS-1$
        newField.m_useIncomingFieldNameAsMongoFieldName = XMLHandler
            .getTagValue(fieldNode,
                "use_incoming_field_name_as_mongo_field_name") //$NON-NLS-1$
            .equalsIgnoreCase("Y"); //$NON-NLS-1$
        newField.m_updateMatchField = XMLHandler.getTagValue(fieldNode,
            "update_match_field").equalsIgnoreCase("Y"); //$NON-NLS-1$ //$NON-NLS-2$

        newField.m_modifierUpdateOperation = XMLHandler.getTagValue(fieldNode,
            "modifier_update_operation"); //$NON-NLS-1$
        String policy = XMLHandler.getTagValue(fieldNode, "modifier_policy"); //$NON-NLS-1$
        if (!Const.isEmpty(policy)) {
          newField.m_modifierOperationApplyPolicy = policy;
        }
        String jsonField = XMLHandler.getTagValue(fieldNode, "json_field"); //$NON-NLS-1$
        if (!Const.isEmpty(jsonField)) {
          newField.m_JSON = jsonField.equalsIgnoreCase("Y"); //$NON-NLS-1$
        }

        m_mongoFields.add(newField);
      }
    }

    fields = XMLHandler.getSubNode(stepnode, "mongo_indexes"); //$NON-NLS-1$
    if (fields != null && XMLHandler.countNodes(fields, "mongo_index") > 0) { //$NON-NLS-1$
      int nrfields = XMLHandler.countNodes(fields, "mongo_index"); //$NON-NLS-1$

      m_mongoIndexes = new ArrayList<MongoIndex>();

      for (int i = 0; i < nrfields; i++) {
        Node fieldNode = XMLHandler.getSubNodeByNr(fields, "mongo_index", i); //$NON-NLS-1$

        MongoIndex newIndex = new MongoIndex();

        newIndex.m_pathToFields = XMLHandler.getTagValue(fieldNode,
            "path_to_fields"); //$NON-NLS-1$
        newIndex.m_drop = XMLHandler.getTagValue(fieldNode, "drop") //$NON-NLS-1$
            .equalsIgnoreCase("Y"); //$NON-NLS-1$
        newIndex.m_unique = XMLHandler.getTagValue(fieldNode, "unique") //$NON-NLS-1$
            .equalsIgnoreCase("Y"); //$NON-NLS-1$
        newIndex.m_sparse = XMLHandler.getTagValue(fieldNode, "sparse") //$NON-NLS-1$
            .equalsIgnoreCase("Y"); //$NON-NLS-1$

        m_mongoIndexes.add(newIndex);
      }
    }
  }

  @Override
  public void readRep(Repository rep, ObjectId id_step,
      List<DatabaseMeta> databases, Map<String, Counter> counters)
      throws KettleException {

    m_hostnames = rep.getStepAttributeString(id_step, 0, "mongo_host"); //$NON-NLS-1$
    m_port = rep.getStepAttributeString(id_step, 0, "mongo_port"); //$NON-NLS-1$
    m_useAllReplicaSetMembers = rep.getStepAttributeBoolean(id_step, 0,
        "use_all_replica_members"); //$NON-NLS-1$
    m_username = rep.getStepAttributeString(id_step, 0, "mongo_user"); //$NON-NLS-1$
    m_password = rep.getStepAttributeString(id_step, 0, "mongo_password"); //$NON-NLS-1$
    m_dbName = rep.getStepAttributeString(id_step, 0, "mongo_db"); //$NON-NLS-1$
    m_collection = rep.getStepAttributeString(id_step, 0, "mongo_collection"); //$NON-NLS-1$
    m_batchInsertSize = rep.getStepAttributeString(id_step, 0,
        "batch_insert_size"); //$NON-NLS-1$

    m_connectTimeout = rep.getStepAttributeString(id_step, "connect_timeout"); //$NON-NLS-1$
    m_socketTimeout = rep.getStepAttributeString(id_step, "socket_timeout"); //$NON-NLS-1$
    m_readPreference = rep.getStepAttributeString(id_step, "read_preference"); //$NON-NLS-1$
    m_writeConcern = rep.getStepAttributeString(id_step, "write_concern"); //$NON-NLS-1$
    m_wTimeout = rep.getStepAttributeString(id_step, "w_timeout"); //$NON-NLS-1$
    m_journal = rep.getStepAttributeBoolean(id_step, 0, "journaled_writes"); //$NON-NLS-1$

    m_truncate = rep.getStepAttributeBoolean(id_step, 0, "truncate"); //$NON-NLS-1$
    m_upsert = rep.getStepAttributeBoolean(id_step, 0, "upsert"); //$NON-NLS-1$
    m_multi = rep.getStepAttributeBoolean(id_step, 0, "multi"); //$NON-NLS-1$
    m_modifierUpdate = rep.getStepAttributeBoolean(id_step, 0,
        "modifier_update"); //$NON-NLS-1$

    int nrfields = rep.countNrStepAttributes(id_step, "incoming_field_name"); //$NON-NLS-1$

    String writeRetries = rep.getStepAttributeString(id_step, "write_retries"); //$NON-NLS-1$
    if (!Const.isEmpty(writeRetries)) {
      m_writeRetries = writeRetries;
    }
    String writeRetryDelay = rep.getStepAttributeString(id_step,
        "write_retry_delay"); //$NON-NLS-1$
    if (!Const.isEmpty(writeRetryDelay)) {
      m_writeRetryDelay = writeRetryDelay;
    }

    if (nrfields > 0) {
      m_mongoFields = new ArrayList<MongoField>();

      for (int i = 0; i < nrfields; i++) {
        MongoField newField = new MongoField();

        newField.m_incomingFieldName = rep.getStepAttributeString(id_step, i,
            "incoming_field_name"); //$NON-NLS-1$
        newField.m_mongoDocPath = rep.getStepAttributeString(id_step, i,
            "mongo_doc_path"); //$NON-NLS-1$

        newField.m_useIncomingFieldNameAsMongoFieldName = rep
            .getStepAttributeBoolean(id_step, i,
                "use_incoming_field_name_as_mongo_field_name"); //$NON-NLS-1$
        newField.m_updateMatchField = rep.getStepAttributeBoolean(id_step, i,
            "update_match_field"); //$NON-NLS-1$
        newField.m_modifierUpdateOperation = rep.getStepAttributeString(
            id_step, i, "modifier_update_operation"); //$NON-NLS-1$
        String policy = rep.getStepAttributeString(id_step, i,
            "modifier_policy"); //$NON-NLS-1$
        if (!Const.isEmpty(policy)) {
          newField.m_modifierOperationApplyPolicy = policy;
        }
        newField.m_JSON = rep.getStepAttributeBoolean(id_step, i, "json_field"); //$NON-NLS-1$

        m_mongoFields.add(newField);
      }
    }

    nrfields = rep.countNrStepAttributes(id_step, "path_to_fields"); //$NON-NLS-1$
    if (nrfields > 0) {
      m_mongoIndexes = new ArrayList<MongoIndex>();

      for (int i = 0; i < nrfields; i++) {
        MongoIndex newIndex = new MongoIndex();

        newIndex.m_pathToFields = rep.getStepAttributeString(id_step, i,
            "path_to_fields"); //$NON-NLS-1$
        newIndex.m_drop = rep.getStepAttributeBoolean(id_step, i, "drop"); //$NON-NLS-1$
        newIndex.m_unique = rep.getStepAttributeBoolean(id_step, i, "unique"); //$NON-NLS-1$
        newIndex.m_sparse = rep.getStepAttributeBoolean(id_step, i, "sparse"); //$NON-NLS-1$

        m_mongoIndexes.add(newIndex);
      }
    }
  }

  @Override
  public void saveRep(Repository rep, ObjectId id_transformation,
      ObjectId id_step) throws KettleException {

    if (!Const.isEmpty(m_hostnames)) {
      rep.saveStepAttribute(id_transformation, id_step, 0, "mongo_host", //$NON-NLS-1$
          m_hostnames);
    }
    if (!Const.isEmpty(m_port)) {
      rep.saveStepAttribute(id_transformation, id_step, 0, "mongo_port", m_port); //$NON-NLS-1$
    }

    rep.saveStepAttribute(id_transformation, id_step,
        "use_all_replica_members", m_useAllReplicaSetMembers); //$NON-NLS-1$

    if (!Const.isEmpty(m_username)) {
      rep.saveStepAttribute(id_transformation, id_step, 0, "mongo_user", //$NON-NLS-1$
          m_username);
    }
    if (!Const.isEmpty(m_password)) {
      rep.saveStepAttribute(id_transformation, id_step, 0, "password", //$NON-NLS-1$
          Encr.encryptPasswordIfNotUsingVariables(m_password));
    }
    if (!Const.isEmpty(m_dbName)) {
      rep.saveStepAttribute(id_transformation, id_step, 0, "mongo_db", m_dbName); //$NON-NLS-1$
    }
    if (!Const.isEmpty(m_collection)) {
      rep.saveStepAttribute(id_transformation, id_step, 0, "mongo_collection", //$NON-NLS-1$
          m_collection);
    }
    if (!Const.isEmpty(m_batchInsertSize)) {
      rep.saveStepAttribute(id_transformation, id_step, 0, "batch_insert_size", //$NON-NLS-1$
          m_batchInsertSize);
    }

    rep.saveStepAttribute(id_transformation, id_step, "connect_timeout", //$NON-NLS-1$
        m_connectTimeout);
    rep.saveStepAttribute(id_transformation, id_step, "socket_timeout", //$NON-NLS-1$
        m_socketTimeout);
    rep.saveStepAttribute(id_transformation, id_step, "read_preference", //$NON-NLS-1$
        m_readPreference);
    rep.saveStepAttribute(id_transformation, id_step, "write_concern", //$NON-NLS-1$
        m_writeConcern);
    rep.saveStepAttribute(id_transformation, id_step, "w_timeout", m_wTimeout); //$NON-NLS-1$
    rep.saveStepAttribute(id_transformation, id_step, "journaled_writes", //$NON-NLS-1$
        m_journal);

    rep.saveStepAttribute(id_transformation, id_step, 0, "truncate", m_truncate); //$NON-NLS-1$
    rep.saveStepAttribute(id_transformation, id_step, 0, "upsert", m_upsert); //$NON-NLS-1$
    rep.saveStepAttribute(id_transformation, id_step, 0, "multi", m_multi); //$NON-NLS-1$
    rep.saveStepAttribute(id_transformation, id_step, 0, "modifier_update", //$NON-NLS-1$
        m_modifierUpdate);
    rep.saveStepAttribute(id_transformation, id_step, 0, "write_retries", //$NON-NLS-1$
        m_writeRetries);
    rep.saveStepAttribute(id_transformation, id_step, 0, "write_retry_delay", //$NON-NLS-1$
        m_writeRetryDelay);

    if (m_mongoFields != null && m_mongoFields.size() > 0) {
      for (int i = 0; i < m_mongoFields.size(); i++) {
        MongoField field = m_mongoFields.get(i);

        rep.saveStepAttribute(id_transformation, id_step, i,
            "incoming_field_name", field.m_incomingFieldName); //$NON-NLS-1$
        rep.saveStepAttribute(id_transformation, id_step, i, "mongo_doc_path", //$NON-NLS-1$
            field.m_mongoDocPath);
        rep.saveStepAttribute(id_transformation, id_step, i,
            "use_incoming_field_name_as_mongo_field_name", //$NON-NLS-1$
            field.m_useIncomingFieldNameAsMongoFieldName);
        rep.saveStepAttribute(id_transformation, id_step, i,
            "update_match_field", field.m_updateMatchField); //$NON-NLS-1$
        rep.saveStepAttribute(id_transformation, id_step, i,
            "modifier_update_operation", field.m_modifierUpdateOperation); //$NON-NLS-1$
        rep.saveStepAttribute(id_transformation, id_step, i, "modifier_policy", //$NON-NLS-1$
            field.m_modifierOperationApplyPolicy);
        rep.saveStepAttribute(id_transformation, id_step, i, "json_field", //$NON-NLS-1$
            field.m_JSON);
      }
    }

    if (m_mongoIndexes != null && m_mongoIndexes.size() > 0) {
      for (int i = 0; i < m_mongoIndexes.size(); i++) {
        MongoIndex mongoIndex = m_mongoIndexes.get(i);

        rep.saveStepAttribute(id_transformation, id_step, i, "path_to_fields", //$NON-NLS-1$
            mongoIndex.m_pathToFields);
        rep.saveStepAttribute(id_transformation, id_step, i, "drop", //$NON-NLS-1$
            mongoIndex.m_drop);
        rep.saveStepAttribute(id_transformation, id_step, i, "unique", //$NON-NLS-1$
            mongoIndex.m_unique);
        rep.saveStepAttribute(id_transformation, id_step, i, "sparse", //$NON-NLS-1$
            mongoIndex.m_sparse);
      }
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.pentaho.di.trans.step.BaseStepMeta#getDialogClassName()
   */
  @Override
  public String getDialogClassName() {
    return "org.pentaho.di.trans.steps.mongodboutput.MongoDbOutputDialog"; //$NON-NLS-1$
  }
}
