/*
 *************************************************************************
 * Copyright (c) 2004, 2008 Actuate Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Actuate Corporation  - initial API and implementation
 *  
 *************************************************************************
 */ 
package org.eclipse.birt.data.engine.executor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;

import org.eclipse.birt.core.data.DataType;
import org.eclipse.birt.core.data.DataTypeUtil;
import org.eclipse.birt.data.engine.api.DataEngineContext;
import org.eclipse.birt.data.engine.api.IOdaDataSetDesign;
import org.eclipse.birt.data.engine.api.IQueryDefinition;
import org.eclipse.birt.data.engine.core.DataException;
import org.eclipse.birt.data.engine.core.security.ThreadSecurity;
import org.eclipse.birt.data.engine.executor.QueryExecutionStrategyUtil.Strategy;
import org.eclipse.birt.data.engine.executor.cache.ResultSetCache;
import org.eclipse.birt.data.engine.executor.dscache.DataSetResultCache;
import org.eclipse.birt.data.engine.executor.transform.CachedResultSet;
import org.eclipse.birt.data.engine.executor.transform.SimpleResultSet;
import org.eclipse.birt.data.engine.i18n.ResourceConstants;
import org.eclipse.birt.data.engine.impl.DataEngineImpl;
import org.eclipse.birt.data.engine.impl.DataEngineSession;
import org.eclipse.birt.data.engine.impl.IExecutorHelper;
import org.eclipse.birt.data.engine.impl.StopSign;
import org.eclipse.birt.data.engine.impl.document.StreamWrapper;
import org.eclipse.birt.data.engine.odaconsumer.ColumnHint;
import org.eclipse.birt.data.engine.odaconsumer.ParameterHint;
import org.eclipse.birt.data.engine.odaconsumer.PreparedStatement;
import org.eclipse.birt.data.engine.odaconsumer.ResultSet;
import org.eclipse.birt.data.engine.odi.IDataSourceQuery;
import org.eclipse.birt.data.engine.odi.IEventHandler;
import org.eclipse.birt.data.engine.odi.IParameterMetaData;
import org.eclipse.birt.data.engine.odi.IPreparedDSQuery;
import org.eclipse.birt.data.engine.odi.IResultClass;
import org.eclipse.birt.data.engine.odi.IResultIterator;
import org.eclipse.birt.data.engine.odi.IResultObject;
import org.eclipse.datatools.connectivity.oda.IBlob;
import org.eclipse.datatools.connectivity.oda.IClob;

/**
 *	Structure to hold info of a custom field. 
 */
final class CustomField
{
    String 	name;
    int dataType = -1;
    
    CustomField( String name, int dataType)
    {
        this.name = name;
        this.dataType = dataType;
    }
    
    CustomField()
    {}
    
    public int getDataType()
    {
        return dataType;
    }
    
    public void setDataType(int dataType)
    {
        this.dataType = dataType;
    }
    
    public String getName()
    {
        return name;
    }
    
    public void setName(String name)
    {
        this.name = name;
    }
}

/**
 * Structure to hold Parameter binding info
 * @author lzhu
 *
 */
class ParameterBinding
{
	private String name;
	private int position = -1;
	private Object value;
	
	ParameterBinding( String name, int position, Object value )
	{
		this.name = name;
		this.value = value;
		this.position = position;
	}
	
	ParameterBinding( int position, Object value )
	{
		this.position = position;
		this.value = value;
	}
	
	public int getPosition()
	{
		return position;
	}
	
	public String getName()
	{
		return name;
	}

	public Object getValue()
	{
		return value;
	}
}

/**
 * Implementation of ODI's IDataSourceQuery interface
 */
public class DataSourceQuery extends BaseQuery implements IDataSourceQuery, IPreparedDSQuery
{
    protected DataSource 		dataSource;
    protected String			queryText;
    protected String			queryType;
    protected PreparedStatement	odaStatement;
    
    // Collection of ColumnHint objects
    protected Collection		resultHints;
    
    // Collection of CustomField objects
    protected Collection		customFields;
    
    protected IResultClass		resultMetadata;
    
    // Names (or aliases) of columns in the projected result set
    protected String[]			projectedFields;
	
	// input/output parameter hints (collection of ParameterHint objects)
	private Collection parameterHints;
    
	// input parameter values
	private Collection inputParamValues;
	
	// Properties added by addProperty()
	private ArrayList propNames;
	private ArrayList propValues;
	
	private DataEngineSession session;
	/**
	 * Constructor. 
	 * 
	 * @param dataSource
	 * @param queryType
	 * @param queryText
	 */
    DataSourceQuery( DataSource dataSource, String queryType, String queryText, DataEngineSession session )
    {
        this.dataSource = dataSource;
        this.queryText = queryText;
        this.queryType = queryType;
        this.session = session;
    }

    /*
     * @see org.eclipse.birt.data.engine.odi.IDataSourceQuery#setResultHints(java.util.Collection)
     */
    public void setResultHints(Collection columnDefns)
    {
        resultHints = columnDefns;
    }

    /*
     * @see org.eclipse.birt.data.engine.odi.IDataSourceQuery#setResultProjection(java.lang.String[])
     */
    public void setResultProjection(String[] fieldNames) throws DataException
    {
        if ( fieldNames == null || fieldNames.length == 0 )
            return;		// nothing to set
        this.projectedFields = fieldNames;
    }
    
	public void setParameterHints( Collection parameterHints )
	{
        // assign to placeholder, for use later during prepare()
		this.parameterHints = parameterHints;
	}

    /*
     * @see org.eclipse.birt.data.engine.odi.IDataSourceQuery#addProperty(java.lang.String, java.lang.String)
     */
    public void addProperty(String name, String value ) throws DataException
    {
    	if ( name == null )
    		throw new NullPointerException("Property name is null");
    	
    	// Must be called before prepare() per interface spec
        if ( odaStatement != null )
            throw new DataException( ResourceConstants.QUERY_HAS_PREPARED );
    	
   		if ( propNames == null )
   		{
   			assert propValues == null;
   			propNames = new ArrayList();
   			propValues = new ArrayList();
   		}
   		assert propValues != null;
   		propNames.add( name );
   		propValues.add( value );
    }

    /*
     * @see org.eclipse.birt.data.engine.odi.IDataSourceQuery#declareCustomField(java.lang.String, int)
     */
    public void declareCustomField( String fieldName, int dataType ) throws DataException
    {
        if ( fieldName == null || fieldName.length() == 0 )
            throw new DataException( ResourceConstants.CUSTOM_FIELD_EMPTY );
        
        if ( customFields == null )
        {
            customFields = new ArrayList();
        }
        else
        {
        	Iterator cfIt = customFields.iterator( );
			while ( cfIt.hasNext( ) )
			{
				CustomField cf = (CustomField) cfIt.next();
				if ( cf.name.equals( fieldName ) )
				{
					throw new DataException( ResourceConstants.DUP_CUSTOM_FIELD_NAME, fieldName );
				}
			}
        }
        
        customFields.add( new CustomField( fieldName, dataType ) );
    }

    /* (non-Javadoc)
     * @see org.eclipse.birt.data.engine.odi.IDataSourceQuery#prepare()
     */
    public IPreparedDSQuery prepare() throws DataException
    {
        if ( odaStatement != null )
            throw new DataException( ResourceConstants.QUERY_HAS_PREPARED );

        odaStatement = dataSource.prepareStatement( queryText, queryType );
        
        // Add custom properties
        addProperties();
        
        // Add parameter defns. This step must be done before odaStatement.setColumnsProjection()
        // for some jdbc driver need to carry out a query execution before the metadata can be achieved
        // and only when the Parameters are successfully set the query execution can succeed.
        addParameterDefns();
        
        IOdaDataSetDesign design = null;
    	if( session.getDataSetCacheManager( ).getCurrentDataSetDesign( ) instanceof IOdaDataSetDesign )
    		design = (IOdaDataSetDesign)session.getDataSetCacheManager( ).getCurrentDataSetDesign( );
    	
        if ( design != null )
		{
			if ( canAccessResultSetByName( design ) )
			{
				// Ordering is important for the following operations. Column hints
				// should be defined
				// after custom fields are declared (since hints may be given to
				// those custom fields).
				// Column projection comes last because it needs hints and
				// custom
				// column information
				addCustomFields( design.getPrimaryResultSetName( ), odaStatement );
				addColumnHints( design.getPrimaryResultSetName( ), odaStatement );

				if ( this.projectedFields != null )
					odaStatement.setColumnsProjection( design.getPrimaryResultSetName( ), this.projectedFields );
			}
			else if( canAccessResultSetByNumber( design ) )
			{
				addCustomFields( design.getPrimaryResultSetNumber( ), odaStatement );
				addColumnHints( design.getPrimaryResultSetNumber( ), odaStatement );

				if ( this.projectedFields != null )
					odaStatement.setColumnsProjection( design.getPrimaryResultSetNumber( ), this.projectedFields );
			}
			else
			{
				prepareColumns( );
			}
		}else
		{
			prepareColumns( );
		}
        
		//Here the "max rows" means the max number of rows that can fetch from data source.
		odaStatement.setMaxRows( this.getRowFetchLimit( ) );
		
        // If ODA can provide result metadata, get it now
        try
        {
        	resultMetadata = getMetaData( (IOdaDataSetDesign)session.getDataSetCacheManager( ).getCurrentDataSetDesign( ), odaStatement );

        }
        catch ( DataException e )
        {
            // Assume metadata not available at prepare time; ignore the exception
        	resultMetadata = null;
        }
        
        return this;
    }

	private boolean canAccessResultSetByName( IOdaDataSetDesign design )
			throws DataException
	{
		return design.getPrimaryResultSetName( ) != null && odaStatement.supportsNamedResults( );
	}

    private boolean canAccessResultSetByNumber( IOdaDataSetDesign design )
            throws DataException
    {
        return design.getPrimaryResultSetNumber( ) > 0 && odaStatement.supportsMultipleResultSets( );
    }
	
	private void prepareColumns( ) throws DataException
	{
		addCustomFields( odaStatement );
		addColumnHints( odaStatement );

		if ( this.projectedFields != null )
			odaStatement.setColumnsProjection( this.projectedFields );
	}

    /**
     * 
     * @param design
     * @param odaStatement
     * @return
     * @throws DataException
     */
    private IResultClass getMetaData( IOdaDataSetDesign design, PreparedStatement odaStatement ) throws DataException
    {
    	IResultClass result = null;
    	if ( design != null )
		{
			if ( canAccessResultSetByName( design ) )
			{
				try
				{
					result = odaStatement.getMetaData( design.getPrimaryResultSetName( ) );
				}
				catch ( DataException e )
				{
					throw new DataException( ResourceConstants.ERROR_HAPPEN_WHEN_RETRIEVE_RESULTSET,
							design.getPrimaryResultSetName( ) );
				}
				
			}
			else if ( canAccessResultSetByNumber( design ) )
			{
				try
				{
					result = odaStatement.getMetaData( design.getPrimaryResultSetNumber( ) );
				}
				catch ( DataException e )
				{
					throw new DataException( ResourceConstants.ERROR_HAPPEN_WHEN_RETRIEVE_RESULTSET,
							design.getPrimaryResultSetNumber( ) );
				}
			}
		}
		if( result == null )
			result = odaStatement.getMetaData();
		return result;
    }
    
    /** 
     * Adds custom properties to oda statement being prepared 
     */
    private void addProperties() throws DataException
	{
    	assert odaStatement != null;
    	if ( propNames != null )
    	{
    		assert propValues != null;
    		
    		Iterator it_name = propNames.iterator();
    		Iterator it_val = propValues.iterator();
    		while ( it_name.hasNext())
    		{
    			assert it_val.hasNext();
    			String name = (String) it_name.next();
    			String val = (String) it_val.next();
    			odaStatement.setProperty( name, val );
    		}
    	}
	}
      
	/** 
	 * Adds input and output parameter hints to odaStatement
	 */
	private void addParameterDefns() throws DataException
	{
		assert odaStatement!= null;
		
		if ( this.parameterHints == null )
		    return;	// nothing to add

		// iterate thru the collection to add parameter hints
		Iterator it = this.parameterHints.iterator( );
		while ( it.hasNext( ) )
		{
			ParameterHint parameterHint = (ParameterHint) it.next();
			odaStatement.addParameterHint( parameterHint );
			
			//If the parameter is input parameter then add it to input value list.
			if ( parameterHint.isInputMode( ) )
			{
                Class paramHintDataType = parameterHint.getDataType();
                
                // since a Date may have extended types,
                // use the type of Date that is most effective for data conversion
                if( paramHintDataType == Date.class )
                    paramHintDataType = parameterHint.getEffectiveDataType( 
                                            dataSource.getDriverName(), queryType );
                
				Object inputValue = parameterHint.getDefaultInputValue( );
				// neither IBlob nor IClob will be converted
				if ( paramHintDataType != IBlob.class
						&& paramHintDataType != IClob.class )
					inputValue = convertToValue( parameterHint.getDefaultInputValue( ),
							paramHintDataType );
				if ( parameterHint.getPosition( ) <= 0 || odaStatement.supportsNamedParameter( ))
				{
					this.setInputParamValue( parameterHint.getName( ), parameterHint.getPosition( ),
							inputValue );
					
				}
				else
				{
					this.setInputParamValue( parameterHint.getPosition( ),
							inputValue );
				}
			}			
		}
		this.setInputParameterBinding();
	}
	
	/**
	 * @param inputParamName
	 * @param paramValue
	 * @throws DataException
	 */
	private void setInputParamValue( String inputParamName, int position, Object paramValue )
			throws DataException
	{

		ParameterBinding pb = new ParameterBinding( inputParamName, position, paramValue );
		getInputParamValues().add( pb );
	}

	/**
	 * @param inputParamPos
	 * @param paramValue
	 * @throws DataException
	 */
	private void setInputParamValue( int inputParamPos, Object paramValue )
			throws DataException
	{
		ParameterBinding pb = new ParameterBinding( inputParamPos, paramValue );
		getInputParamValues().add( pb );
	}
	
	/**
	 * Declares custom fields on Oda statement
	 * 
	 * @param stmt
	 * @throws DataException
	 */
    private void addCustomFields( PreparedStatement stmt ) throws DataException
	{
    	if ( this.customFields != null )
    	{
    		Iterator it = this.customFields.iterator( );
    		while ( it.hasNext( ) )
    		{
    			CustomField customField = (CustomField) it.next( );
    			stmt.declareCustomColumn( customField.getName( ),
    				DataType.getClass( customField.getDataType() ) );
    		}
    	}
	}
    
    private void addCustomFields( String rsetName, PreparedStatement stmt ) throws DataException
	{
    	if ( this.customFields != null )
    	{
    		Iterator it = this.customFields.iterator( );
    		while ( it.hasNext( ) )
    		{
    			CustomField customField = (CustomField) it.next( );
    			stmt.declareCustomColumn( rsetName, customField.getName( ),
    				DataType.getClass( customField.getDataType() ) );
    		}
    	}
	}
    
    private void addCustomFields( int rsetNumber, PreparedStatement stmt ) throws DataException
	{
    	if ( this.customFields != null )
    	{
    		Iterator it = this.customFields.iterator( );
    		while ( it.hasNext( ) )
    		{
    			CustomField customField = (CustomField) it.next( );
    			stmt.declareCustomColumn( rsetNumber, customField.getName( ),
    				DataType.getClass( customField.getDataType() ) );
    		}
    	}
	}
    /**
     * Adds Odi column hints to ODA statement
     *  
     * @param stmt
     * @throws DataException
     */
    private void addColumnHints( PreparedStatement stmt ) throws DataException
	{
    	assert stmt != null;
    	if ( resultHints == null || resultHints.size() == 0 )
    		return;
    	Iterator it = resultHints.iterator();
    	while ( it.hasNext())
    	{
    		ColumnHint colHint = prepareOdiHint( (IDataSourceQuery.ResultFieldHint) it.next() );
   			stmt.addColumnHint( colHint );
    	}
	}

    private void addColumnHints( String rsetName, PreparedStatement stmt ) throws DataException
	{
    	assert stmt != null;
    	if ( resultHints == null || resultHints.size() == 0 )
    		return;
    	Iterator it = resultHints.iterator();
    	while ( it.hasNext())
    	{
    		ColumnHint colHint = prepareOdiHint( (IDataSourceQuery.ResultFieldHint) it.next() );
   			stmt.addColumnHint( rsetName, colHint );
    	}
	}

    private void addColumnHints( int rsetNumber, PreparedStatement stmt ) throws DataException
	{
    	assert stmt != null;
    	if ( resultHints == null || resultHints.size() == 0 )
    		return;
    	Iterator it = resultHints.iterator();
    	while ( it.hasNext())
    	{
    		ColumnHint colHint = prepareOdiHint( (IDataSourceQuery.ResultFieldHint) it.next() );
   			stmt.addColumnHint( rsetNumber, colHint );
    	}
	}

	private ColumnHint prepareOdiHint( IDataSourceQuery.ResultFieldHint odiHint )
	{
		ColumnHint colHint = new ColumnHint( odiHint.getName() );
		colHint.setAlias( odiHint.getAlias() );
		if ( odiHint.getDataType( ) == DataType.ANY_TYPE )
			colHint.setDataType( null );
		else
			colHint.setDataType( DataType.getClass( odiHint.getDataType( ) ) );  
		colHint.setNativeDataType( odiHint.getNativeDataType() );
		if ( odiHint.getPosition() > 0 )
			colHint.setPosition( odiHint.getPosition());
		return colHint;
	}
       
	/*
	 * @see org.eclipse.birt.data.engine.odi.IPreparedDSQuery#getResultClass()
	 */
    public IResultClass getResultClass() 
    {
        // Note the return value can be null if resultMetadata was 
        // not available during prepare() time
        return resultMetadata;
    }

    /*
     * @see org.eclipse.birt.data.engine.odi.IPreparedDSQuery#getParameterMetaData()
     */
    public Collection getParameterMetaData()
			throws DataException
	{
        if ( odaStatement == null )
			throw new DataException( ResourceConstants.QUERY_HAS_NOT_PREPARED );
        
        Collection odaParamsInfo = odaStatement.getParameterMetaData();
        if ( odaParamsInfo == null || odaParamsInfo.isEmpty() )
            return null;
        
        // iterates thru the most up-to-date collection, and
        // wraps each of the odaconsumer parameter metadata object
        ArrayList paramMetaDataList = new ArrayList( odaParamsInfo.size() );
        Iterator odaParamMDIter = odaParamsInfo.iterator();
        while ( odaParamMDIter.hasNext() )
        {
            org.eclipse.birt.data.engine.odaconsumer.ParameterMetaData odaMetaData = 
                (org.eclipse.birt.data.engine.odaconsumer.ParameterMetaData) odaParamMDIter.next();
            paramMetaDataList.add( new ParameterMetaData( odaMetaData ) );
        }
        return paramMetaDataList;
	}
    
    /**
     * Return the input parameter value list
     * 
     * @return
     */
	private Collection getInputParamValues()
	{
	    if ( inputParamValues == null )
	        inputParamValues = new ArrayList();
	    return inputParamValues;
	}

	/*
	 * @see org.eclipse.birt.data.engine.odi.IPreparedDSQuery#execute()
	 */
    public IResultIterator execute( IEventHandler eventHandler, StopSign stopSign )
			throws DataException
	{
    	assert odaStatement != null;
    	
    	IResultIterator ri = null;

    	this.setInputParameterBinding();
    	
    	IOdaDataSetDesign design = null;
    	if( session.getDataSetCacheManager( ).getCurrentDataSetDesign( ) instanceof IOdaDataSetDesign )
    		design = (IOdaDataSetDesign)session.getDataSetCacheManager( ).getCurrentDataSetDesign( );
    	if ( session.getDataSetCacheManager( ).doesSaveToCache( ) )
		{
			int fetchRowLimit = 0;
			if ( design != null )
			{
				fetchRowLimit = session.getDataSetCacheManager( )
						.getCurrentDataSetDesign( )
						.getRowFetchLimit( );
			}
			int cacheCountConfig = session.getDataSetCacheManager( ).getCacheCountConfig( );
			if ( fetchRowLimit != 0
					&& fetchRowLimit < cacheCountConfig )
			{
				odaStatement.setMaxRows( fetchRowLimit );
			}
			else if ( cacheCountConfig > 0 )
			{
				odaStatement.setMaxRows( cacheCountConfig );
			}
		}
		
		OdaQueryExecutor queryExecutor = new OdaQueryExecutor( odaStatement );
		Thread executionThread = ThreadSecurity.createThread( queryExecutor );
		executionThread.start( );

		boolean success = false;
		while ( !stopSign.isStopped( ) )
		{
			if ( queryExecutor.isClose( ) )
			{
				if ( queryExecutor.collectException( ) == null )
				{
					success = true;
					break;
				}
				else
					throw queryExecutor.collectException( );
			}
		}

    	if( !success )
    	{
    		//Indicate the query executor thread should close the statement after the query execution.
    		queryExecutor.setCloseStatementAfterExecution( );
    		//Return the dummy result iterator implementation.
    		return new IResultIterator(){

				public void close( ) throws DataException
				{
					// TODO Auto-generated method stub
					
				}

				
				public void doSave( StreamWrapper streamsWrapper,
						boolean isSubQuery ) throws DataException
				{
					// TODO Auto-generated method stub
					
				}

				
				public void first( int groupingLevel ) throws DataException
				{
					// TODO Auto-generated method stub
					
				}

				
				public Object getAggrValue( String aggrName )
						throws DataException
				{
					// TODO Auto-generated method stub
					return null;
				}

				
				public int getCurrentGroupIndex( int groupLevel )
						throws DataException
				{
					// TODO Auto-generated method stub
					return 0;
				}

				
				public IResultObject getCurrentResult( ) throws DataException
				{
					// TODO Auto-generated method stub
					return null;
				}

				
				public int getCurrentResultIndex( ) throws DataException
				{
					// TODO Auto-generated method stub
					return 0;
				}

				
				public int getEndingGroupLevel( ) throws DataException
				{
					// TODO Auto-generated method stub
					return 0;
				}

				
				public IExecutorHelper getExecutorHelper( )
				{
					// TODO Auto-generated method stub
					return null;
				}

				
				public int[] getGroupStartAndEndIndex( int groupLevel )
						throws DataException
				{
					// TODO Auto-generated method stub
					return null;
				}

				
				public IResultClass getResultClass( ) throws DataException
				{
					// TODO Auto-generated method stub
					return null;
				}

				
				public ResultSetCache getResultSetCache( )
				{
					// TODO Auto-generated method stub
					return null;
				}

				
				public int getRowCount( ) throws DataException
				{
					// TODO Auto-generated method stub
					return 0;
				}

				
				public int getStartingGroupLevel( ) throws DataException
				{
					// TODO Auto-generated method stub
					return 0;
				}

				
				public void last( int groupingLevel ) throws DataException
				{
					// TODO Auto-generated method stub
					
				}

				
				public boolean next( ) throws DataException
				{
					// TODO Auto-generated method stub
					return false;
				}};
    	}
		
		ResultSet rs = null;
		
		if ( design != null )
		{
			if ( canAccessResultSetByName( design ) )
			{
				try
				{

					rs = odaStatement.getResultSet( design.getPrimaryResultSetName( ) );
				}
				catch ( DataException e )
				{
					throw new DataException( ResourceConstants.ERROR_HAPPEN_WHEN_RETRIEVE_RESULTSET,
							design.getPrimaryResultSetName( ) );
				}
			}
			else if ( canAccessResultSetByNumber( design ) )
			{
				try
				{
					rs = odaStatement.getResultSet( design.getPrimaryResultSetNumber( ) );
				}
				catch ( DataException e )
				{
					throw new DataException( ResourceConstants.ERROR_HAPPEN_WHEN_RETRIEVE_RESULTSET,
							design.getPrimaryResultSetNumber( ) );
				}
			}
		}
		if( rs == null )
		{
			rs = odaStatement.getResultSet( );
		}
		// If we did not get a result set metadata at prepare() time, get it now
		if ( resultMetadata == null )
		{
			resultMetadata = rs.getMetaData( );
			if ( resultMetadata == null )
				throw new DataException( ResourceConstants.METADATA_NOT_AVAILABLE );
		}
		
		// Initialize CachedResultSet using the ODA result set
		if ( session.getDataSetCacheManager( ).doesSaveToCache( ) == false )
		{
	    	if ( session.getEngineContext( ).getMode( ) == DataEngineContext.DIRECT_PRESENTATION
					&& this.getQueryDefinition( ) instanceof IQueryDefinition )
			{
				IQueryDefinition queryDefn = (IQueryDefinition) this.getQueryDefinition( );

				if ( QueryExecutionStrategyUtil.getQueryExecutionStrategy( queryDefn,
						queryDefn.getDataSetName( ) == null
								? null
								: ( (DataEngineImpl) this.session.getEngine( ) ).getDataSetDesign( queryDefn.getDataSetName( ) ) ) == Strategy.Simple )
				{
					IResultIterator it = new SimpleResultSet( this,
							rs,
							resultMetadata,
							eventHandler,
							stopSign );
					eventHandler.handleEndOfDataSetProcess( it );
					return it;
				}
			}
	    	
			ri = new CachedResultSet( this,
					resultMetadata,
					rs,
					eventHandler,
					session,
					stopSign );
		}
		else
			ri = new CachedResultSet( this,
					resultMetadata,
					new DataSetResultCache( rs, resultMetadata, session ),
					eventHandler, session, stopSign );
		
		if ( ri != null && ri instanceof CachedResultSet )
			( (CachedResultSet) ri ).setOdaResultSet( rs );

		return ri;
    }
    
    private class OdaQueryExecutor implements Runnable
    {
    	private boolean close = false;
    	private PreparedStatement statement;
    	private DataException exception;
    	private boolean closeStatementAfterExecution = false;
    	
    	OdaQueryExecutor( PreparedStatement statement )
    	{
    		this.statement = statement;
    	}
    	
		
		public void run( )
		{
			try
			{
				this.statement.execute( );
				if( this.closeStatementAfterExecution )
					this.statement.close( );
			}
			catch ( DataException e )
			{
				this.exception = e;
			}
			this.close = true;
		}

		public void setCloseStatementAfterExecution( )
		{
			this.closeStatementAfterExecution = true;
		}
		
		/**
		 * Collect the exception throw during statement execution.
		 * 
		 * @return
		 */
		public DataException collectException()
		{
			return this.exception;
		}
		
		/**
		 * Indicate whether the thread has finished execution.
		 * @return
		 */
		public boolean isClose()
		{
			return this.close;
		}
    }
    
    /**
     *  set input parameter bindings
     */
    private void setInputParameterBinding() throws DataException
    {
    	assert odaStatement!= null;
    	
    	//		 set input parameter bindings
		Iterator inputParamValueslist = getInputParamValues().iterator( );
		while ( inputParamValueslist.hasNext( ) )
		{
			ParameterBinding paramBind = (ParameterBinding) inputParamValueslist.next( );
			if ( paramBind.getPosition( ) <= 0 || odaStatement.supportsNamedParameter( ))
			{
				try
				{
					odaStatement.setParameterValue( paramBind.getName( ),
							paramBind.getValue( ) );
				}
				catch ( DataException e )
				{
					if ( paramBind.getPosition( ) <= 0 )
					{
						throw e;
					}
					else
					{
						odaStatement.setParameterValue( paramBind.getPosition( ),
								paramBind.getValue( ) );
					}
				}
			}
			else
			{
				odaStatement.setParameterValue( paramBind.getPosition( ),
						paramBind.getValue() );
			}
		}
    }
    
	/*
	 * @see org.eclipse.birt.data.engine.odi.IPreparedDSQuery#getParameterValue(int)
	 */
	public Object getOutputParameterValue( int index ) throws DataException
	{
		assert odaStatement != null;
		
		int newIndex = getCorrectParamIndex( index );
		return odaStatement.getParameterValue( newIndex );
	}

	/*
	 * @see org.eclipse.birt.data.engine.odi.IPreparedDSQuery#getParameterValue(java.lang.String)
	 */
	public Object getOutputParameterValue( String name ) throws DataException
	{
		assert odaStatement != null;
				
		checkOutputParamNameValid( name );
		return odaStatement.getParameterValue( name );
	}
    
	/**
	 * In oda layer, it does not differentiate the value retrievation of input
	 * parameter value and ouput parameter value. They will be put in a same
	 * sequence list. However, in odi layer, we need to clearly distinguish them
	 * since only retrieving output parameter is suppored and it should be based
	 * on its own output parameter index. Therefore, this method will do such a
	 * conversion from the output parameter index to the parameter index.
	 * 
	 * @param index based on output parameter order
	 * @return index based on the whole parameters order
	 * @throws DataException
	 */
	private int getCorrectParamIndex( int index ) throws DataException
	{
		if ( index <= 0 )
			throw new DataException( ResourceConstants.INVALID_OUTPUT_PARAMETER_INDEX, new Integer(index) );
		
		int newIndex = 0; // 1-based
		int curOutputIndex = 0; // 1-based
		
		Collection collection = getParameterMetaData( );
		if ( collection != null )
		{
			Iterator it = collection.iterator( );
			while ( it.hasNext( ) )
			{
				newIndex++;
				
				IParameterMetaData metaData = (IParameterMetaData) it.next( );
				if ( metaData.isOutputMode( ).booleanValue( ) == true )
				{
					curOutputIndex++;
					
					if ( curOutputIndex == index )
						break;
				}
			}
		}

		if ( curOutputIndex < index )
			throw new DataException( ResourceConstants.OUTPUT_PARAMETER_OUT_OF_BOUND,new Integer(index));

		return newIndex;
	}
	
	/**
	 * Validate the name of output parameter
	 * 
	 * @param name
	 * @throws DataException
	 */
	private void checkOutputParamNameValid( String name ) throws DataException
	{
		assert name != null;

		boolean isValid = false;

		Collection collection = getParameterMetaData( );
		if ( collection != null )
		{
			Iterator it = collection.iterator( );
			while ( it.hasNext( ) )
			{
				IParameterMetaData metaData = (IParameterMetaData) it.next( );

				String paramName = metaData.getName( );
				if ( paramName.equals( name ) )
				{
					isValid = metaData.isOutputMode( ).booleanValue( );
					break;
				}
			}
		}

		if ( isValid == false )
			throw new DataException( ResourceConstants.INVALID_OUTPUT_PARAMETER_NAME, name );
	}
	
	/*
	 * @see org.eclipse.birt.data.engine.odi.IQuery#close()
	 */
    public void close()
    {
        if ( odaStatement != null )
        {
        	this.dataSource.closeStatement( odaStatement );
	        odaStatement = null;
        }
        
        this.dataSource = null;
        // TODO: close all CachedResultSets created by us
    }
    
    /**
     * convert the String value to Object according to it's datatype.
     *  
     * @param inputValue
     * @param type
     * @return
     * @throws DataException
     */
    private static Object convertToValue( String inputValue, Class typeClass )
			throws DataException
	{
		try
		{
			return DataTypeUtil.convert( inputValue, typeClass);
		}
		catch ( Exception ex )
		{
			throw new DataException( ResourceConstants.CANNOT_CONVERT_PARAMETER_TYPE,
					ex,
					new Object[]{
							inputValue, typeClass
					} );
		}
	}
    
}
