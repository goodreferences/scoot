/**
 * Copyright (c) 2012, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided
 * that the following conditions are met:
 *
 *    Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *    following disclaimer.
 *
 *    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and
 *    the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *    Neither the name of salesforce.com, inc. nor the names of its contributors may be used to endorse or
 *    promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.scoot.scripter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;

import com.salesforce.scoot.HBaseSchemaAttribute;
import com.salesforce.scoot.HBaseSchemaDiff;
import com.salesforce.scoot.HBaseSchemaDiff.ChangeType;
import com.salesforce.scoot.HBaseSchemaDiff.HBaseSchemaChange;
import com.salesforce.scoot.HBaseSchemaDiff.PropertyChange;

/**
 * Using a schema diff object, output a ruby script that verifies the existing schema state,
 * and then patches the cluster to install the new schema state, and then validates that
 * it worked correctly.
 */
public class HBaseRubySchemaPatchScripter {
  
  private final HBaseSchemaDiff diff;
  private final StringBuilder script = new StringBuilder();

  public HBaseRubySchemaPatchScripter(HBaseSchemaDiff diff) {
    this.diff = diff;
  }

  public String generateScript() {
    scriptHeaders();
    scriptPreValidations();
    scriptChanges();
    scriptPostValidations();
    scriptFooters();
    return getScript();
  }

  /**
   * Get the script that has been generated by running generateScript
   */
  private String getScript() {
    return this.script.toString();
  }

  /**
   * Shorthand
   */
  private void s(String toScript){
    script.append(toScript + "\n");
  }
  
  private void scriptHeaders() {
    
    Map<ChangeType, List<HBaseSchemaChange>> m = diff.getTableChangesByType();
    s("###############################################################################");
    s("# HBase Schema Update Script");
    s("#");
    s("# Summary:");
    s("#");
    int size = m.get(ChangeType.CREATE).size();
    s("#  * Create " + size + " table" + (size !=1 ? "s" : "") + (size > 0 ? ":" : "."));
    for (HBaseSchemaChange c : m.get(ChangeType.CREATE)){
      s("#       " + c.tableName);
    }
    s("#");
    size = m.get(ChangeType.ALTER).size();
    s("#  * Alter " + size + " table" + (size !=1 ? "s" : "") + (size > 0 ? ":" : "."));
    for (HBaseSchemaChange c : m.get(ChangeType.ALTER)){
      s("#       " + c.tableName);
      // write the properties out in sorted order
      List<String> lp = new ArrayList<String>();
      for (PropertyChange pc : c.propertyChanges) lp.add("property change: " + pc.toString());
      Collections.sort(lp);
      for (String pc : lp) s("#       " + pc);
    }
    s("#");
    size = m.get(ChangeType.DROP).size();
    s("#  * Drop " + size + " table" + (size !=1 ? "s" : "") + (size > 0 ? ":" : "."));
    for (HBaseSchemaChange c : m.get(ChangeType.DROP)){
      s("#       " + c.tableName);
    }
    s("#");
    size = m.get(ChangeType.IGNORE).size();
    s("#  * Ignore " + size + " table" + (size !=1 ? "s" : "") + (size > 0 ? ":" : "."));
    for (HBaseSchemaChange c : m.get(ChangeType.IGNORE)){
      s("#       " + c.tableName);
    }
    s("###############################################################################");
    s("");
    s("###############################################################################");
    s("# Initialization");
    s("###############################################################################");
    s("include Java");
    s("import org.apache.hadoop.hbase.HBaseConfiguration");
    s("import org.apache.hadoop.hbase.HColumnDescriptor");
    s("import org.apache.hadoop.hbase.HConstants");
    s("import org.apache.hadoop.hbase.HTableDescriptor");
    s("import org.apache.hadoop.hbase.client.HBaseAdmin");
    s("import org.apache.hadoop.hbase.client.HTable");
    s("import org.apache.hadoop.hbase.util.Bytes");
    s("");
    s("conf = HBaseConfiguration.new");
    s("admin = HBaseAdmin.new(conf)");
    s("preErrors = Array.new");
    s("preWarnings = Array.new");
    s("postErrors = Array.new");
    s("");
    s("###############################################################################");
    s("# Utility methods"); 
    s("###############################################################################");
    s("");
    s("def compare(errs, obj, action, attr, val)");
    s("    if (obj.getValue(attr).to_s != val)");
    s("        errs << \"Object '#{obj.getNameAsString()}', which is targeted for #{action} by this script, should have had a value of \\\"#{val}\\\" for #{attr}, but it was \\\"#{obj.getValue(attr)}\\\" instead.\\n\"");
    s("    end");
    s("end");
    s("");
  }
  
  private void scriptPreValidations() {

    s("###############################################################################");
    s("# Pre Validation");
    s("#");
    s("# This step makes sure that the existing schema on the cluster matches what you");
    s("# think should be there. It will emit warnings for problems that won't make the");
    s("# script fail; it will emit errors and exit if it encounters any problems that");
    s("# will make the script fail."); 
    s("###############################################################################");

    for (HBaseSchemaChange c : diff.getTableChanges()){
      switch (c.type) {
        case CREATE:
          scriptVerifyTableAbsent(c.tableName, "create", true);
          break;
        case ALTER:
          scriptVerifyTablePresent(c.tableName, "alter", true);
          scriptVerifyTableMatches(c.oldTable, "alter", true); // alters will error out if something doesn't match
          break;
        case DROP:
          scriptVerifyTablePresent(c.tableName, "drop", true);
          scriptVerifyTableMatches(c.oldTable, "drop", false);  // drops will only warn if something doesn't match
          break;
        case IGNORE:
          break;
      }
    }
    s("");
    s("# If any pre-validations had errors, report them and exit the script.");
    s("if (preErrors.length > 0)");
    s("    puts \"There were #{preErrors.length} error(s) and #{preWarnings.length} warning(s) during table pre-validation:\"");
    s("    print \"#{preErrors.collect{|msg| \"Error: \" + msg}}\"");
    s("    print \"#{preWarnings.collect{|msg| \"Warning: \" + msg}}\"");
    s("    raise");
    s("    exit");
    s("elsif (preWarnings.length > 0)");
    s("    puts \"Pre-validations successful with #{preWarnings.length} warnings:\"");
    s("    print \"#{preWarnings.collect{|msg| \"Warning: \" + msg}}\"");
    s("else");
    s("    puts \"Pre-validations successful.\"");
    s("end");
    s("");
  }
  
  private void scriptVerifyTableAbsent(String tableName, String operationName, boolean shouldThrowError) {
    String errorCollectionName = shouldThrowError ? "preErrors" : "preWarnings";
    s("# Table '" + tableName + "' should not exist");
    s("tablename = \"" + tableName + "\"");
    s("if admin.tableExists(tablename)");
    s("    " + errorCollectionName + " << \"Table '#{tablename}' should not already exist, but it does.\\n\"");
    s("end");
    s("");
  }

  private void scriptVerifyTablePresent(String tableName, String operationName, boolean shouldThrowError) {
    String errorCollectionName = shouldThrowError ? "preErrors" : "preWarnings";
    s("# Table '" + tableName + "' should exist");
    s("tablename = \"" + tableName + "\"");
    s("if !admin.tableExists(tablename)");
    s("    " + errorCollectionName + " << \"Table '#{tablename}' should exist, but it does not.\\n\"");
    s("end");
    s("");
  }

  private void scriptVerifyTableMatches(HTableDescriptor oldTable, String operationName, boolean shouldThrowError) {
    String errorCollectionName = shouldThrowError ? "preErrors" : "preWarnings";
    s("# Table '" + oldTable.getNameAsString() + "' will " + (shouldThrowError ? "error" : "warn") + " if it doesn't match the expected definition.");
    s("if admin.tableExists(tablename)");
    s("    table = admin.getTableDescriptor(tablename.bytes.to_a)");
    for (Entry<String,String> p : getSortedStringEntries(oldTable.getValues())){
      s("    compare(" + errorCollectionName + ", table, \"" + operationName + "\", \"" + p.getKey() + "\", \"" + escapeDoubleQuotes(p.getValue()) + "\")");
    }
    // now descend into child objects
    for (HColumnDescriptor c : oldTable.getColumnFamilies()){
      s("    # Column family: " + c.getNameAsString());
      s("    cfname = \"" + c.getNameAsString() + "\"");
      s("    cf = table.getFamily(cfname.bytes.to_a)");

      for (Entry<String,String> p : getSortedStringEntries(c.getValues())){
        s("    compare(" + errorCollectionName + ", cf, \"" + operationName + "\", \"" + p.getKey() + "\", \"" + escapeDoubleQuotes(p.getValue()) + "\")");
      }
    }    
    s("end");
    s("");
    
  }

  /**
   * Get a sorted map of the given ImmutableBytesWritable map as strings
   */
  private Set<Entry<String,String>> getSortedStringEntries(Map<ImmutableBytesWritable, ImmutableBytesWritable> m) {
    Map<String,String> result = new TreeMap<String,String>();
    for (Entry<ImmutableBytesWritable, ImmutableBytesWritable> e : m.entrySet()){
      result.put(Bytes.toString(e.getKey().get()), Bytes.toString(e.getValue().get()));
    }
    return result.entrySet();
  }

  /**
   * Change " to \" in a string
   */
  private String escapeDoubleQuotes(String value) {
    return value.replace("\"".subSequence(0, 1), "\\\"".subSequence(0, 2));
  }

  private void scriptChanges() {
    
    s("###############################################################################");
    s("# Modifications");
    s("#");
    s("# This step actually modifies the schema on the cluster.");
    s("###############################################################################");
    s("");
    
    for (HBaseSchemaChange c : diff.getTableChanges()){
      switch (c.type) {
        case CREATE:
          scriptTableAdd(c.newTable);
          break;
      case DROP:
          scriptTableDrop(c.oldTable);
          break;
      case ALTER:
          scriptTableAlter(c.newTable);
          break;
      case IGNORE:
          // Nothing to do!
          break;
      }
    }
    s("puts \"Table creations & modifications successful.\"");
    s("");
 }
  
  private void scriptTableAdd(HTableDescriptor newTable) {
    s("# Create Table: " + newTable.getNameAsString());
    s("tablename = \"" + newTable.getNameAsString() + "\"");
    s("table = HTableDescriptor.new(tablename)");
    s("#set table properties");
    for (Entry<String,String> entry : getSortedStringEntries(newTable.getValues())){
      s("table.setValue(\"" + entry.getKey() + "\", \"" + escapeDoubleQuotes(entry.getValue()) + "\")");
    }
    for (HColumnDescriptor cf : newTable.getColumnFamilies()){
      s("cf = HColumnDescriptor.new(\"" + cf.getNameAsString() + "\")");
      for (Entry<String,String> entry : getSortedStringEntries(cf.getValues())){
        s("cf.setValue(\"" + entry.getKey() + "\", \"" + escapeDoubleQuotes(entry.getValue()) + "\")");
      }
      s("table.addFamily(cf)");
    }
    s("puts \"Creating table '#{tablename}' ... \"");
    
    // If we need to pre-split, that's a special method call
    if (newTable.getValue(HBaseSchemaAttribute.NUMREGIONS.name()) != null){
    	s("admin.createTable(table, Bytes.toBytes(\"\\x00\"), Bytes.toBytes(\"\\xFF\"), " + newTable.getValue(HBaseSchemaAttribute.NUMREGIONS.name()) + ")");
    } else {
    	s("admin.createTable(table)");
    }
    s("puts \"Created table '#{tablename}'\"");
    s("");
  }

  private void scriptTableDrop(HTableDescriptor oldTable) {
    s("# Drop Table: " + oldTable.getNameAsString());
    s("tablename = \"" + oldTable.getNameAsString() + "\"");
    s("table = HTableDescriptor.new(tablename)");
    s("if admin.tableExists(tablename)");
    s("  if admin.isTableEnabled(tablename)");
    s("    puts \"Disabling table '#{tablename}' prior to dropping it ...\"");
    s("    admin.disableTable(tablename)");
    s("  end");
    s("    puts \"Dropping table '#{tablename}' ...\"");
    s("  admin.deleteTable(tablename)");
    s("end");
    s("puts \"Dropped table '#{tablename}'\"");
    s("");
  }

  private void scriptTableAlter(HTableDescriptor newTable) {
    s("# Modify table: " + newTable.getNameAsString());
    s("tablename = \"" + newTable.getNameAsString() + "\"");
    s("table = admin.getTableDescriptor(tablename.bytes.to_a)");
    for (Entry<String,String> entry : getSortedStringEntries(newTable.getValues())){
      s("table.setValue(\"" + entry.getKey() + "\", \"" + escapeDoubleQuotes(entry.getValue()) + "\")");
    }
    for (HColumnDescriptor cf : newTable.getColumnFamilies()){
      s("cf = HColumnDescriptor.new(\"" + cf.getNameAsString() + "\")");
      for (Entry<String,String> entry : getSortedStringEntries(cf.getValues())){
        s("cf.setValue(\"" + entry.getKey() + "\", \"" + escapeDoubleQuotes(entry.getValue()) + "\")");
      }
      s("table.addFamily(cf)");
    }
    s("puts \"Disabling table '#{tablename}' prior to modification ...\"");
    s("admin.disableTable(tablename)");
    s("puts \"Modifying table '#{tablename}' ...\"");
    s("admin.modifyTable(tablename.bytes.to_a, table)");
    s("puts \"Enabling table '#{tablename}' after modification ...\"");
    s("admin.enableTable(tablename)");
    s("puts \"Modified table '#{tablename}\"");
    s("");
  }

  private void scriptPostValidations() {
    
    s("###############################################################################");
    s("# Post Validation");
    s("#");
    s("# This step ensures that changes were successful, and that the resulting schema");
    s("# on the cluster matches what you want to be there.");
    s("###############################################################################");

    for (HBaseSchemaChange c : diff.getTableChanges()){
      switch (c.type) {
      case CREATE:
        scriptVerifyTablePresent(c.tableName, "create", true);
        scriptVerifyTableMatches(c.newTable, "create", true);  
        break;
      case ALTER:
        scriptVerifyTablePresent(c.tableName, "alter", true);
        scriptVerifyTableMatches(c.newTable, "alter", true); 
        break;
      case DROP:
        scriptVerifyTableAbsent(c.tableName, "drop", true);
        break;
      case IGNORE:
        break;
    }

    }
    s("puts \"Post-validation successful.\"");
    s("");
  }

  private void scriptFooters() {
    s("puts \"Script complete. Share and enjoy.\"");
    s("exit");
  }

}
