package za.co.cporm.provider;

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import za.co.cporm.model.CPOrm;
import za.co.cporm.model.CPOrmConfiguration;
import za.co.cporm.model.CPOrmDatabase;
import za.co.cporm.model.generate.TableDetails;
import za.co.cporm.model.util.ManifestHelper;
import za.co.cporm.provider.util.UriMatcherHelper;
import za.co.cporm.util.CPOrmLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The base content provided that will expose all of the model objects.
 * Objects are expose in the form of authority/table_name/*
 */
public class CPOrmContentProvider extends ContentProvider {

    public static final String PARAMETER_OFFSET = "OFFSET";
    public static final String PARAMETER_LIMIT = "LIMIT";
    public static final String PARAMETER_SYNC = "IS_SYNC";
    public static final String PARAMETER_CHANGE_TYPE = "CPORM_CHANGE_TYPE";
    public static final String PARAMETER_NOTIFY_CHANGES = "NOTIFY_CHANGES";

    private final ThreadLocal<Boolean> isBatchOperation = new ThreadLocal<>();
    private final ThreadLocal<Set<Uri>> changedUri = new ThreadLocal<>();

    protected CPOrmConfiguration cPOrmConfiguration;
    protected CPOrmDatabase database;
    protected UriMatcherHelper uriMatcherHelper;
    protected boolean debugEnabled;

    @Override
    public boolean onCreate() {

        cPOrmConfiguration = ManifestHelper.getConfiguration(getContext());
        database = new CPOrmDatabase(getContext(), cPOrmConfiguration);

        debugEnabled = cPOrmConfiguration.isQueryLoggingEnabled();
        return true;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);

        uriMatcherHelper = new UriMatcherHelper(getContext(), info.authority);
        uriMatcherHelper.init(getContext(), database.getcPOrmConfiguration(), database.getTableDetailsCache());
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getReadableDatabase();
        String limit = constructLimit(uri);
        Boolean distinct = uri.getBooleanQueryParameter("DISTINCT", false);
        String groupBy = uri.getQueryParameter("GROUP_BY");
        String having = uri.getQueryParameter("HAVING");

        if (debugEnabled) {
            CPOrmLog.d("********* Query **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Projection: " + Arrays.toString(projection));
            CPOrmLog.d("Selection: " + selection);
            CPOrmLog.d("Args: " + Arrays.toString(selectionArgs));
            CPOrmLog.d("Sort: " + sortOrder);
            CPOrmLog.d("Limit: " + limit);
            CPOrmLog.d("Distinct: " + distinct);
            CPOrmLog.d("Group By: " + groupBy);
            CPOrmLog.d("Having: " + having);
        }

        Cursor cursor;

        if (uriMatcherHelper.isSingleItemRequested(uri)) {

            String itemId = uri.getLastPathSegment();
            cursor = db.query(true, tableDetails.getTableName(), projection, tableDetails.getPrimaryKeyClause(), new String[]{itemId}, null, null, null, "1");
        } else
            cursor = db.query(distinct, tableDetails.getTableName(), projection, selection, selectionArgs, groupBy, having, sortOrder, limit);

        cursor.setNotificationUri(getContext().getContentResolver(), uri);

        return cursor;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder, CancellationSignal cancellationSignal) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getReadableDatabase();
        String limit = constructLimit(uri);
        Boolean distinct = uri.getBooleanQueryParameter("DISTINCT", false);
        String groupBy = uri.getQueryParameter("GROUP_BY");
        String having = uri.getQueryParameter("HAVING");

        if (debugEnabled) {
            CPOrmLog.d("********* Query **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Projection: " + Arrays.toString(projection));
            CPOrmLog.d("Selection: " + selection);
            CPOrmLog.d("Args: " + Arrays.toString(selectionArgs));
            CPOrmLog.d("Sort: " + sortOrder);
            CPOrmLog.d("Limit: " + limit);
            CPOrmLog.d("Distinct: " + distinct);
            CPOrmLog.d("Group By: " + groupBy);
            CPOrmLog.d("Having: " + having);
        }

        Cursor cursor;

        if (uriMatcherHelper.isSingleItemRequested(uri)) {

            String itemId = uri.getLastPathSegment();
            cursor = db.query(true, tableDetails.getTableName(), projection, tableDetails.getPrimaryKeyClause(), new String[]{itemId}, null, null, null, "1", cancellationSignal);
        } else
            cursor = db.query(distinct, tableDetails.getTableName(), projection, selection, selectionArgs, groupBy, having, sortOrder, limit, cancellationSignal);

        cursor.setNotificationUri(getContext().getContentResolver(), uri);

        return cursor;
    }

    @Override
    public String getType(@NonNull Uri uri) {

        return uriMatcherHelper.getType(uri);
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues contentValues) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getWritableDatabase();

        if (debugEnabled) {
            CPOrmLog.d("********* Insert **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Content Values: " + contentValues);
        }

        long insertId = db.insertOrThrow(tableDetails.getTableName(), null, contentValues);

        if (insertId == -1)
            throw new IllegalArgumentException("Failed to insert row for into table " + tableDetails.getTableName() + " using values " + contentValues);

        Uri insertedUri;
        TableDetails.ColumnDetails primaryKeyColumn = tableDetails.findPrimaryKeyColumn();
        if (primaryKeyColumn.isAutoIncrement()) {
            insertedUri = uriMatcherHelper.generateSingleItemUri(tableDetails, insertId)
                    .buildUpon()
                    .appendQueryParameter(PARAMETER_CHANGE_TYPE, CPOrm.ChangeType.INSERT.toString())
                    .build();
        } else {

            String primaryKeyValue = contentValues.getAsString(primaryKeyColumn.getColumnName());
            insertedUri = uriMatcherHelper.generateSingleItemUri(tableDetails, primaryKeyValue)
                    .buildUpon()
                    .appendQueryParameter(PARAMETER_CHANGE_TYPE, CPOrm.ChangeType.INSERT.toString())
                    .build();
        }

        if(!isBatchOperation()) notifyChanges(insertedUri, tableDetails);
        else changedUri.get().add(insertedUri);

        return insertedUri;
    }

    @Override
    public int delete(@NonNull Uri uri, String where, String[] args) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getWritableDatabase();

        if (debugEnabled) {
            CPOrmLog.d("********* Delete **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Where: " + where);
            CPOrmLog.d("Args: " + Arrays.toString(args));
        }

        int deleteCount;

        if (uriMatcherHelper.isSingleItemRequested(uri)) {

            String itemId = uri.getLastPathSegment();
            TableDetails.ColumnDetails primaryKeyColumn = tableDetails.findPrimaryKeyColumn();
            deleteCount = db.delete(tableDetails.getTableName(), primaryKeyColumn.getColumnName() + " = ?", new String[]{itemId});
        } else deleteCount = db.delete(tableDetails.getTableName(), where, args);

        if (deleteCount == 0)
            return deleteCount;

        Uri deleteUri = uri.buildUpon().appendQueryParameter(PARAMETER_CHANGE_TYPE, CPOrm.ChangeType.DELETE.toString()).build();
        if(!isBatchOperation()) notifyChanges(deleteUri, tableDetails);
        else changedUri.get().add(deleteUri);

        return deleteCount;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues contentValues, String where, String[] args) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getWritableDatabase();

        if (debugEnabled) {
            CPOrmLog.d("********* Update **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Content Values: " + contentValues);
            CPOrmLog.d("Where: " + where);
            CPOrmLog.d("Args: " + Arrays.toString(args));
        }

        int updateCount;

        if (uriMatcherHelper.isSingleItemRequested(uri)) {

            String itemId = uri.getLastPathSegment();
            TableDetails.ColumnDetails primaryKeyColumn = tableDetails.findPrimaryKeyColumn();
            updateCount = db.update(tableDetails.getTableName(), contentValues, primaryKeyColumn.getColumnName() + " = ?", new String[]{itemId});
        } else updateCount = db.update(tableDetails.getTableName(), contentValues, where, args);

        if (updateCount > 0 && shouldChangesBeNotified(tableDetails, contentValues)) {
            Uri updateUri = uri.buildUpon().appendQueryParameter(PARAMETER_CHANGE_TYPE, CPOrm.ChangeType.UPDATE.toString()).build();
            if(!isBatchOperation()) notifyChanges(updateUri, tableDetails);
            else changedUri.get().add(updateUri);
        }

        return updateCount;
    }

    @Override
    public int bulkInsert(@NonNull Uri uri, @NonNull ContentValues[] values) {

        int length = values.length;
        if (length == 0)
            return 0;

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getWritableDatabase();

        if (debugEnabled) {
            CPOrmLog.d("********* Bulk Insert **********");
            CPOrmLog.d("Uri: " + uri);
        }

        int count = 0;

        try {
            db.beginTransactionNonExclusive();
            String tableName = tableDetails.getTableName();
            for (int i = 0; i < length; i++) {

                db.insertOrThrow(tableName, null, values[i]);
                count++;

                if (count % 100 == 0)
                    db.yieldIfContendedSafely();
            }

            db.setTransactionSuccessful();

            notifyChanges(uri.buildUpon().appendQueryParameter(PARAMETER_CHANGE_TYPE, CPOrm.ChangeType.INSERT.toString()).build(), tableDetails);
        } finally {
            db.endTransaction();
        }

        return count;
    }

    @NonNull
    @Override
    public ContentProviderResult[] applyBatch(@NonNull ArrayList<ContentProviderOperation> operations) throws OperationApplicationException {

        if (debugEnabled) {
            CPOrmLog.d("********* Apply Batch **********");
            CPOrmLog.d("Operations Count: " + operations.size());
        }

        isBatchOperation.set(true);
        changedUri.set(new LinkedHashSet<Uri>());

        boolean success = false;
        ContentProviderResult[] contentProviderResults = null;
        SQLiteDatabase db = database.getWritableDatabase();
        try {
            db.beginTransactionNonExclusive();
            contentProviderResults = super.applyBatch(operations);
            db.setTransactionSuccessful();
            success = true;
            return contentProviderResults;
        } finally {
            db.endTransaction();

            if(success && changedUri.get() != null) {
                for (Uri uri : changedUri.get()) {

                    if (uri != null) {
                        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
                        notifyChanges(uri, tableDetails);
                    }
                }
            }
            isBatchOperation.set(false);
            changedUri.remove();
        }
    }

    private String constructLimit(Uri uri) {

        String offsetParam = uri.getQueryParameter(PARAMETER_OFFSET);
        String limitParam = uri.getQueryParameter(PARAMETER_LIMIT);

        Integer offset = null;
        Integer limit = null;

        if (!TextUtils.isEmpty(offsetParam) && TextUtils.isDigitsOnly(offsetParam)) {
            offset = Integer.valueOf(offsetParam);
        }
        if (!TextUtils.isEmpty(limitParam) && TextUtils.isDigitsOnly(limitParam)) {
            limit = Integer.valueOf(limitParam);
        }

        if (limit == null && offset == null)
            return null;

        StringBuilder limitStatement = new StringBuilder();

        if (limit != null && offset != null) {
            limitStatement.append(offset);
            limitStatement.append(",");
            limitStatement.append(limit);
        } else if (limit != null) {
            limitStatement.append(limit);
        } else throw new IllegalArgumentException("A limit must also be provided when setting an offset");

        return limitStatement.toString();
    }

    private boolean shouldChangesBeNotified(TableDetails tableDetails, ContentValues contentValues) {

        boolean notify = false;

        for (String columnName : contentValues.keySet()) {

            TableDetails.ColumnDetails column = tableDetails.findColumn(columnName);
            if (column != null)
                notify = notify || column.notifyChanges();
        }

        return notify;
    }

    private void notifyChanges(Uri uri, TableDetails tableDetails) {

        if(!uri.getBooleanQueryParameter(PARAMETER_NOTIFY_CHANGES, true))
            return;

        Boolean sync = uri.getBooleanQueryParameter(PARAMETER_SYNC, true);
        getContext().getContentResolver().notifyChange(uri, null, sync);

        List<Class<?>> changeListeners = tableDetails.getChangeListeners();
        if (!changeListeners.isEmpty()) {

            int size = changeListeners.size();
            for (int i = 0; i < size; i++) {
                Class<?> changeListener = changeListeners.get(i);
                TableDetails changeListenerDetails = database.getTableDetailsCache().findTableDetails(getContext(), changeListener);

                if (changeListenerDetails == null)
                    continue;

                //Change listeners are registered on views, so the entire view needs to be updated if changes to its data occurs
                Uri changeUri = uriMatcherHelper.generateItemUri(changeListenerDetails);
                getContext().getContentResolver().notifyChange(changeUri, null, sync);
            }
        }
    }

    private boolean isBatchOperation(){
        return this.isBatchOperation.get() != null && this.isBatchOperation.get();
    }
}
