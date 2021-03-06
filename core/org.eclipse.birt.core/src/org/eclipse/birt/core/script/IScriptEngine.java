/*******************************************************************************
 * Copyright (c) 2008 Actuate Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Actuate Corporation  - initial API and implementation
 *******************************************************************************/

package org.eclipse.birt.core.script;

import java.util.Locale;

import org.eclipse.birt.core.exception.BirtException;

import com.ibm.icu.util.TimeZone;

public interface IScriptEngine
{
	/**
	 * Returns the script engine factory which created this engine instance.
	 */
	public IScriptEngineFactory getFactory( );

	/**
	 * Returns name of script.
	 * 
	 * @return
	 */
	public String getScriptLanguage( );

	/**
	 * Evaluates a compiled script.
	 * 
	 * @param script
	 * @return
	 * @throws BirtException 
	 */
	Object evaluate( ScriptContext scriptContext, ICompiledScript script )
			throws BirtException;

	/**
	 * Compiles the script for later execution.
	 * 
	 * @param script
	 * @param id
	 * @param lineNumber
	 * @return
	 */
	ICompiledScript compile( ScriptContext scriptContext, String fileName,
			int lineNumber, String script ) throws BirtException;

	/**
	 * Sets time zone.
	 */
	void setTimeZone(TimeZone zone );

	/**
	 * Sets locale.
	 */
	void setLocale( Locale locale );

	/**
	 * Sets application class loader.
	 */
	void setApplicationClassLoader( ClassLoader loader );

	/**
	 * Closes the engine.
	 */
	void close( );
}
