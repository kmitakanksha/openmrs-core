/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.api.db.hibernate;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Criteria;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Conjunction;
import org.hibernate.criterion.CriteriaSpecification;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.LogicalExpression;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.hibernate.criterion.SimpleExpression;
import org.openmrs.PatientIdentifierType;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.util.OpenmrsConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The PatientSearchCriteria class. It has API to return a criteria from the Patient Name and
 * identifier.
 */
public class PatientSearchCriteria {
	
	private final static Log log = LogFactory.getLog(PatientSearchCriteria.class);
	
	private final SessionFactory sessionFactory;
	
	private final Criteria criteria;
	
	private PersonSearchCriteria personSearchCriteria;
	
	/**
	 * @param sessionFactory
	 * @param criteria
	 */
	public PatientSearchCriteria(SessionFactory sessionFactory, Criteria criteria) {
		this.sessionFactory = sessionFactory;
		this.criteria = criteria;
		this.personSearchCriteria = new PersonSearchCriteria();
	}
	
	/**
	 * Prepare a hibernate criteria for searching patients by name and/or identifier.
	 *
	 * The visibility of this method remains public in order not to break OpenMRS modules that use this method.
	 *
	 * Instead of calling this method consider using {@link org.openmrs.api.PatientService} or
	 * {@link org.openmrs.api.db.PatientDAO}.
	 *
	 * @param name
	 * @param identifier
	 * @param identifierTypes
	 * @param matchIdentifierExactly
	 * @param searchOnNamesOrIdentifiers specifies if the logic should find patients that match the
	 *            name or identifier otherwise find patients that match both the name and identifier
	 * @return {@link Criteria}
	 */
	public Criteria prepareCriteria(String name, String identifier, List<PatientIdentifierType> identifierTypes,
	        boolean matchIdentifierExactly, boolean orderByNames, boolean searchOnNamesOrIdentifiers) {
		
		PatientSearchMode patientSearchMode = getSearchMode(name, identifier, identifierTypes, searchOnNamesOrIdentifiers);
		
		switch (patientSearchMode) {
			case PATIENT_SEARCH_BY_NAME:
				addAliasForName(criteria, orderByNames);
				criteria.add(prepareCriterionForName(name));
				break;
			
			case PATIENT_SEARCH_BY_IDENTIFIER:
				addAliasForIdentifiers(criteria);
				criteria.add(prepareCriterionForIdentifier(identifier, identifierTypes, matchIdentifierExactly));
				break;
			
			case PATIENT_SEARCH_BY_NAME_OR_IDENTIFIER:

				// If only name *or* identifier is provided as a search parameter,
				// the respective value is copied to the empty search parameter.
				//
				// As a consequence, the *single* parameter is used to search for both names and identifiers.
				//
				name = copySearchParameter(identifier, name);
				identifier = copySearchParameter(name, identifier);
				
				addAliasForName(criteria, orderByNames);
				addAliasForIdentifiers(criteria);
				criteria.add(Restrictions.disjunction().add(prepareCriterionForName(name)).add(
				    prepareCriterionForIdentifier(identifier, identifierTypes, matchIdentifierExactly)));
				break;
			
			case PATIENT_SEARCH_BY_NAME_AND_IDENTIFIER:
				addAliasForName(criteria, orderByNames);
				addAliasForIdentifiers(criteria);
				criteria.add(prepareCriterionForName(name));
				criteria.add(prepareCriterionForIdentifier(identifier, identifierTypes, matchIdentifierExactly));
				break;
			
		}
		
		criteria.add(Restrictions.eq("voided", false));
		criteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
		
		log.debug(criteria.toString());
		
		return criteria;
	}
	
	/**
	 * Provides a Hibernate criteria object for searching patients by name, identifier or searchable attribute.
	 *
	 * The visibility of this method is "default" as this method should NOT be called directly by classes other
	 * than org.openmrs.api.db.hibernate.HibernatePatientDAO.
	 *
	 * Instead of calling this method consider using {@link org.openmrs.api.PatientService} or
	 * {@link org.openmrs.api.db.PatientDAO}.
	 *
	 * @param query defines search parameters
	 * @return criteria for searching by name OR identifier OR searchable attributes
	 */
	Criteria prepareCriteria(String query) {
		addAliasForName(criteria, true);
		personSearchCriteria.addAliasForAttribute(criteria);
		addAliasForIdentifiers(criteria);
		
		criteria.add(Restrictions.disjunction().add(prepareCriterionForName(query)).add(prepareCriterionForAttribute(query))
		        .add(prepareCriterionForIdentifier(query, new ArrayList<PatientIdentifierType>(), false)));
		
		criteria.add(Restrictions.eq("voided", false));
		criteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
		
		log.debug(criteria.toString());
		
		return criteria;
	}
	
	/**
	 * @should return source value when target is blank
	 * @should return target value when target is non-blank
	 */
	String copySearchParameter(String source, String target) {
		if (!StringUtils.isBlank(source) && StringUtils.isBlank(target)) {
			return source;
		}
		return target;
	}
	
	/**
	 * @should identify search by name
	 * @should identify search by identifier
	 * @should identify search by identifier type list
	 * @should identify search by identifier and identifier type list
	 * @should identify search by name or identifier
	 * @should identify search by name and identifier
	 */
	PatientSearchMode getSearchMode(String name, String identifier, List<PatientIdentifierType> identifierTypes,
	        boolean searchOnNamesOrIdentifiers) {
		if (searchOnNamesOrIdentifiers) {
			return PatientSearchMode.PATIENT_SEARCH_BY_NAME_OR_IDENTIFIER;
		}
		
		if (!StringUtils.isBlank(name) && StringUtils.isBlank(identifier) && CollectionUtils.isEmpty(identifierTypes)) {
			return PatientSearchMode.PATIENT_SEARCH_BY_NAME;
		}
		
		// de Morgan's law coming to fruition: (!A||!B) <=> !(A&&B)
		//
		if (StringUtils.isBlank(name) && !(StringUtils.isBlank(identifier) && CollectionUtils.isEmpty(identifierTypes))) {
			return PatientSearchMode.PATIENT_SEARCH_BY_IDENTIFIER;
		}
		
		return PatientSearchMode.PATIENT_SEARCH_BY_NAME_AND_IDENTIFIER;
	}
	
	private void addAliasForName(Criteria criteria, boolean orderByNames) {
		criteria.createAlias("names", "name");
		if (orderByNames) {
			criteria.addOrder(Order.asc("name.givenName"));
			criteria.addOrder(Order.asc("name.middleName"));
			criteria.addOrder(Order.asc("name.familyName"));
		}
	}
	
	private void addAliasForIdentifiers(Criteria criteria) {
		criteria.createAlias("identifiers", "ids", CriteriaSpecification.LEFT_JOIN);
	}
	
	/**
	 * Utility method to add identifier expression to an existing criteria
	 *
	 * @param identifier
	 * @param identifierTypes
	 * @param matchIdentifierExactly
	 */
	private Criterion prepareCriterionForIdentifier(String identifier, List<PatientIdentifierType> identifierTypes,
	        boolean matchIdentifierExactly) {
		
		identifier = HibernateUtil.escapeSqlWildcards(identifier, sessionFactory);
		Conjunction conjunction = Restrictions.conjunction();
		
		conjunction.add(Restrictions.eq("ids.voided", false));
		// do the identifier restriction
		if (identifier != null) {
			// if the user wants an exact search, match on that.
			if (matchIdentifierExactly) {
				SimpleExpression matchIdentifier = Restrictions.eq("ids.identifier", identifier);
				if (Context.getAdministrationService().isDatabaseStringComparisonCaseSensitive()) {
					matchIdentifier.ignoreCase();
				}
				conjunction.add(matchIdentifier);
			} else {
				AdministrationService adminService = Context.getAdministrationService();
				String regex = adminService.getGlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_PATIENT_IDENTIFIER_REGEX, "");
				String patternSearch = adminService.getGlobalProperty(
				    OpenmrsConstants.GLOBAL_PROPERTY_PATIENT_IDENTIFIER_SEARCH_PATTERN, "");
				
				// remove padding from identifier search string
				if (Pattern.matches("^\\^.{1}\\*.*$", regex)) {
					identifier = removePadding(identifier, regex);
				}
				
				if (org.springframework.util.StringUtils.hasLength(patternSearch)) {
					conjunction.add(splitAndGetSearchPattern(identifier, patternSearch));
				}
				// if the regex is empty, default to a simple "like" search or if
				// we're in hsql world, also only do the simple like search (because
				// hsql doesn't know how to deal with 'regexp'
				else if (regex.equals("") || HibernateUtil.isHSQLDialect(sessionFactory)) {
					conjunction.add(getCriterionForSimpleSearch(identifier, adminService));
				}
				// if the regex is present, search on that
				else {
					regex = replaceSearchString(regex, identifier);
					conjunction.add(Restrictions.sqlRestriction("identifier regexp ?", regex, Hibernate.STRING));
				}
			}
		}
		
		// TODO add a junit test for patientIdentifierType restrictions
		
		// do the type restriction
		if (!CollectionUtils.isEmpty(identifierTypes)) {
			criteria.add(Restrictions.in("ids.identifierType", identifierTypes));
		}
		
		return conjunction;
	}
	
	/**
	 * Utility method to add prefix and suffix like expression
	 *
	 * @param identifier
	 * @param adminService
	 */
	private Criterion getCriterionForSimpleSearch(String identifier, AdministrationService adminService) {
		String prefix = adminService.getGlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_PATIENT_IDENTIFIER_PREFIX, "");
		String suffix = adminService.getGlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_PATIENT_IDENTIFIER_SUFFIX, "");
		StringBuffer likeString = new StringBuffer(prefix).append(identifier).append(suffix);
		return Restrictions.ilike("ids.identifier", likeString.toString());
	}
	
	/**
	 * Utility method to add search pattern expression to identifier.
	 *
	 * @param identifier
	 * @param patternSearch
	 */
	private Criterion splitAndGetSearchPattern(String identifier, String patternSearch) {
		// split the pattern before replacing in case the user searched on a comma
		List<String> searchPatterns = new ArrayList<String>();
		// replace the @SEARCH@, etc in all elements
		for (String pattern : patternSearch.split(",")) {
			searchPatterns.add(replaceSearchString(pattern, identifier));
		}
		return Restrictions.in("ids.identifier", searchPatterns);
	}
	
	/**
	 * Utility method to remove padding from the identifier.
	 *
	 * @param identifier
	 * @param regex
	 * @return identifier without the padding.
	 */
	private String removePadding(String identifier, String regex) {
		String padding = regex.substring(regex.indexOf("^") + 1, regex.indexOf("*"));
		Pattern pattern = Pattern.compile("^" + padding + "+");
		identifier = pattern.matcher(identifier).replaceFirst("");
		return identifier;
	}
	
	/**
	 * Utility method to add name expressions to criteria.
	 *
	 * @param name
	 */
	private Criterion prepareCriterionForName(String name) {
		
		name = HibernateUtil.escapeSqlWildcards(name, sessionFactory);
		
		Conjunction conjunction = Restrictions.conjunction();
		String[] nameParts = getQueryParts(name);
		if (nameParts.length > 0) {
			StringBuilder multiName = new StringBuilder(nameParts[0]);
			
			for (int i = 0; i < nameParts.length; i++) {
				String singleName = nameParts[i];
				
				if (singleName != null && singleName.length() > 0) {
					Criterion singleNameCriterion = getCriterionForName(singleName);
					Criterion criterion = singleNameCriterion;
					
					if (i > 0) {
						multiName.append(" ");
						multiName.append(singleName);
						Criterion multiNameCriterion = getCriterionForName(multiName.toString());
						criterion = Restrictions.or(singleNameCriterion, multiNameCriterion);
					}
					
					conjunction.add(criterion);
				}
			}
		}
		
		return conjunction;
	}
	
	/**
	 * @should process simple space as separator
	 * @should process comma as separator
	 * @should process mixed separators
	 * @should not return empty name parts
	 * @should reject null as name
	 **/
	String[] getQueryParts(String query) {
		if (query == null) {
			throw new IllegalArgumentException("query must not be null");
		}
		
		query = query.replace(",", " ");
		String[] queryPartArray = query.split(" ");
		
		List<String> queryPartList = new ArrayList<String>();
		for (String queryPart : queryPartArray) {
			if (queryPart.trim().length() > 0) {
				queryPartList.add(queryPart);
			}
		}
		
		return queryPartList.toArray(new String[0]);
	}
	
	/**
	 * Returns a criteria object comparing the given string to each part of the name. <br/>
	 * <br/>
	 * This criteria is essentially:
	 * <p/>
	 *
	 * <pre>
	 * ... where voided = false &amp;&amp; name in (familyName2, familyName, middleName, givenName)
	 * </pre>
	 *
	 * Except when the name provided is less than min characters (usually 3) then we will look for
	 * an EXACT match by default
	 *
	 * @param name
	 * @return {@link LogicalExpression}
	 */
	private Criterion getCriterionForName(String name) {
		if (isShortName(name)) {
			return getCriterionForShortName(name);
		}
		return getCriterionForLongName(name);
	}
	
	/**
	 * @should recognise short name
	 * @should recognise long name
	 */
	Boolean isShortName(String name) {
		Integer minChars = Context.getAdministrationService().getGlobalPropertyValue(
		    OpenmrsConstants.GLOBAL_PROPERTY_MIN_SEARCH_CHARACTERS,
		    OpenmrsConstants.GLOBAL_PROPERTY_DEFAULT_MIN_SEARCH_CHARACTERS);
		
		if (name != null && name.length() < minChars) {
			return Boolean.TRUE;
			
		} else {
			return Boolean.FALSE;
		}
	}
	
	private Criterion getCriterionForShortName(String name) {
		return Restrictions.conjunction().add(Restrictions.eq("name.voided", false)).add(
		    Restrictions.disjunction().add(Restrictions.eq("name.givenName", name).ignoreCase()).add(
		        Restrictions.eq("name.middleName", name).ignoreCase()).add(
		        Restrictions.eq("name.familyName", name).ignoreCase()).add(
		        Restrictions.eq("name.familyName2", name).ignoreCase()));
	}
	
	private Criterion getCriterionForLongName(String name) {
		MatchMode matchMode = getMatchMode();
		
		return Restrictions.conjunction().add(Restrictions.eq("name.voided", false)).add(
		    Restrictions.disjunction().add(Restrictions.like("name.givenName", name, matchMode)).add(
		        Restrictions.like("name.middleName", name, matchMode)).add(
		        Restrictions.like("name.familyName", name, matchMode)).add(
		        Restrictions.like("name.familyName2", name, matchMode)));
	}
	
	/**
	 * @should return start as default match mode
	 * @should return start as configured match mode
	 * @should return anywhere as configured match mode
	 */
	MatchMode getMatchMode() {
		String matchMode = Context.getAdministrationService().getGlobalProperty(
		    OpenmrsConstants.GLOBAL_PROPERTY_PATIENT_SEARCH_MATCH_MODE,
		    OpenmrsConstants.GLOBAL_PROPERTY_PATIENT_SEARCH_MATCH_START);
		
		if (matchMode.equalsIgnoreCase(OpenmrsConstants.GLOBAL_PROPERTY_PATIENT_SEARCH_MATCH_ANYWHERE)) {
			return MatchMode.ANYWHERE;
		}
		return MatchMode.START;
	}
	
	/**
	 * Puts @SEARCH@, @SEARCH-1@, and @CHECKDIGIT@ into the search string
	 *
	 * @param regex the admin-defined search string containing the @..@'s to be replaced
	 * @param identifierSearched the user entered search string
	 * @return substituted search strings.
	 */
	private String replaceSearchString(String regex, String identifierSearched) {
		String returnString = regex.replaceAll("@SEARCH@", identifierSearched);
		if (identifierSearched.length() > 1) {
			// for 2 or more character searches, we allow regex to use last character as check digit
			returnString = returnString.replaceAll("@SEARCH-1@", identifierSearched.substring(0,
			    identifierSearched.length() - 1));
			returnString = returnString.replaceAll("@CHECKDIGIT@", identifierSearched
			        .substring(identifierSearched.length() - 1));
		} else {
			returnString = returnString.replaceAll("@SEARCH-1@", "");
			returnString = returnString.replaceAll("@CHECKDIGIT@", "");
		}
		return returnString;
	}
	
	private Criterion prepareCriterionForAttribute(String query) {
		query = HibernateUtil.escapeSqlWildcards(query, sessionFactory);
		
		Conjunction conjunction = Restrictions.conjunction();
		MatchMode matchMode = personSearchCriteria.getAttributeMatchMode();
		
		String[] queryParts = getQueryParts(query);
		for (String queryPart : queryParts) {
			conjunction.add(personSearchCriteria.prepareCriterionForAttribute(queryPart, matchMode));
		}
		
		return conjunction;
	}
}
