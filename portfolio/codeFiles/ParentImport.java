import java.io.*;
import java.util.*;

import org.apache.ojb.broker.query.*;

import com.x2dev.sis.model.beans.*;
import com.follett.fsc.core.k12.beans.*;
import com.follett.fsc.core.k12.business.ModelBroker;
import com.follett.fsc.core.k12.tools.imports.TextImportJavaSource;
import com.x2dev.utils.*;

import com.follett.fsc.core.k12.web.UserDataContainer;

@SuppressWarnings("serial")
public class ParentImport extends TextImportJavaSource {

  public static final String PARAM_PRIORITY = "priority";
  public static final String PARAM_FILENAME = "filename";
  public static final String PARAM_SCHOOL = "school";

  private ModelBroker mModelBroker;
  private String mPriority;
  private SisOrganization mOrganization;
  private String mSchool;

  private Map<String, X2BaseBean> mStudents;
  private Map<String, X2BaseBean> mParents;

  // Enum that contains the names, in order, of the columns in the csv being imported
  private enum Index {
    SALUTATION, FIRST_NAME, LAST_NAME, SF_CONTACT_ID, SF_RECORD_ID, ASPEN_ID, MAILING_STREET, MAILING_CITY, MAILING_STATE, MAILING_POSTAL, MAILING_COUNTRY, EMAIL, // 12 (L)
    PHONE_HOME, PHONE_BUSINESS, PHONE_OTHER, PHONE_MOBILE, STUDENT_ASPEN_ID, STUDENT_CONTACT_ID, STUDENT_RECORD_ID, STUDENT_FIRST_NAME, STUDENT_LAST_NAME, // 10 (U)
    PARENT_RELATIONSHIP, PARENT_OTHER_RELATIONSHIP, Count
  }

  protected int getFieldCount() {
    return Index.Count.ordinal();
  }

  /**
   * @see com.follett.fsc.core.k12.tools.imports.ImportJavaSource#importData(java.io.File)
   *      <p/>
   *      Entry Point for data file
   */
  protected void importData(final File sourceFile) throws Exception {
    setUseValueWrappers(true);
    setValueWrapper('"');

    final String filename = (String) getParameter(PARAM_FILENAME);

    final File importData = new File("/usr/fsc/tool-staging/ICA/" + filename);
    importData.setReadable(true);

    mModelBroker = new ModelBroker(getPrivilegeSet());

    mPriority = (String) getParameter(PARAM_PRIORITY);

    mOrganization = (SisOrganization) getOrganization();

    mSchool = (String) getParameter(PARAM_SCHOOL);


    populateStudentMap();
    populateParentMap();


    super.importData(importData);
  }

  /**
   * @see com.follett.fsc.core.k12.tools.imports.TextImportJavaSource#importRecord(java.util.List, int)
   *      <p/>
   *      Entry Point for data record (row in data file)
   */
  protected void importRecord(final List<String> record, final int lineNumber) throws Exception {
    if (lineNumber > 1 && !StringUtils.isEmpty(record.get(Index.SF_CONTACT_ID.ordinal()))) {
      // SF Contact ID
      final String studentRecordID = getStudentRecordID(record);

      SisStudent student = (SisStudent) mStudents.get(studentRecordID);
      if (student == null) {
        // find student contact record
        final Criteria studentCriteria = new Criteria();
        studentCriteria.addEqualTo("person.PSN_FIELDB_003", studentRecordID);
        final QueryByCriteria stuQuery = new QueryByCriteria(SisStudent.class, studentCriteria);
        student = (SisStudent) getBroker().getBeanByQuery(stuQuery);
      }

      if (student != null) {
        importParent(student, record, lineNumber);
      }
    }
  }

  /**
   * @see com.follett.fsc.core.k12.tools.ToolJavaSource#saveState(com.follett.fsc.core.k12.web.UserDataContainer)
   */
  @Override
  protected void saveState(final UserDataContainer userData) throws X2BaseException {
    super.saveState(userData);
    setUseValueWrappers(false);
  }

  protected void importParent(final SisStudent student, final List<String> record, final int lineNumber) throws Exception {
    setStateSideSponsor(student, record);

    SisPerson parent = (SisPerson) mParents.get(getSalesforceContactID(record));

    if (parent == null) {
      parent = (SisPerson) X2BaseBean.newInstance(SisPerson.class, ((SisUser) getUser()).getPersistenceKey());

      //physical address
      final SisAddress address = (SisAddress) X2BaseBean.newInstance(SisAddress.class, ((SisUser) getUser()).getPersistenceKey());
      setAddressFields(address, record, lineNumber);

      parent.setPhysicalAddressOid(address.getOid());
      setPersonFields(parent, record, lineNumber);

      incrementInsertCount();
    } else {
      SisAddress physAddress = parent.getPhysicalAddress();

      if (physAddress == null) {
        physAddress = (SisAddress) X2BaseBean.newInstance(SisAddress.class, ((SisUser) getUser()).getPersistenceKey());
      }
      setAddressFields(physAddress, record, lineNumber);

      parent.setPhysicalAddressOid(physAddress.getOid());
      setPersonFields(parent, record, lineNumber);

      incrementMatchCount();
      incrementUpdateCount();
    }

    importContact(parent, student, record);

//        student.addToContacts(contact);
    mModelBroker.saveBeanForced(student);

  }

  protected void importContact(final SisPerson parent, final SisStudent student, final List<String> record) {
    // get contacts or make a new one
    Contact con = getContactFromSFID(parent.getFieldB002());

    if (con == null) {
      // create contact
      con = (Contact) X2BaseBean.newInstance(Contact.class, ((SisUser) getUser()).getPersistenceKey());
      con.setPersonOid(parent.getOid());
    }

    con.setContactTypeCode(Contact.CONTACT_TYPE_STUDENT);
    con.setOrganization1Oid(mOrganization.getOid());
    mModelBroker.saveBeanForced(con);

    //get student contacts
    // find contact in contact list
    final Collection<StudentContact> contacts = student.getContacts();
    StudentContact contact = null;
    if (contacts == null) {
      createStudentContact(con, student, record);
    } else { // loop through contacts to find correct one

      for (StudentContact c : contacts) {
        final String cOid = c.getContactOid();
        if (con != null && cOid != null && cOid.equals(con.getOid())) {
          contact = c;
          break;
        }
      }

      if (contact == null) {
        createStudentContact(con, student, record);
      } else {
        updateStudentContact(contact, record);
      }
    }
  }

  protected void createStudentContact(final Contact con, final SisStudent student, final List<String> record) {
    final StudentContact contact = (StudentContact) X2BaseBean.newInstance(StudentContact.class, getUser().getPersistenceKey());

    contact.setContactOid(con.getOid());
    contact.setStudentOid(student.getOid());
    contact.setEmergencyPriority(Integer.parseInt(mPriority));
    contact.setRelationshipCode("Parent");
    contact.setReceiveEmailIndicator(true);
    contact.setFieldB001(record.get(Index.PARENT_RELATIONSHIP.ordinal()));
    contact.setFieldD001(record.get(Index.PARENT_OTHER_RELATIONSHIP.ordinal()));

    mModelBroker.saveBeanForced(contact);
  }

  protected void updateStudentContact(final StudentContact contact, final List<String> record) {
    contact.setRelationshipCode("Parent");
    contact.setFieldB001(record.get(Index.PARENT_RELATIONSHIP.ordinal()));
    contact.setFieldD001(record.get(Index.PARENT_OTHER_RELATIONSHIP.ordinal()));

    contact.setEmergencyPriority(Integer.parseInt(mPriority));
    contact.setReceiveEmailIndicator(true);

    mModelBroker.saveBeanForced(contact);
  }

  protected Contact getContactFromSFID(final String sfID) {
    // find student contact record
    final Criteria criteria = new Criteria();
    criteria.addEqualTo("person.PSN_FIELDB_002", sfID);
    final QueryByCriteria query = new QueryByCriteria(Contact.class, criteria);
    final Contact con = (Contact) getBroker().getBeanByQuery(query);

    return con;
  }

  protected void setPersonFields(final SisPerson person, final List<String> record, final int lineNumber) throws Exception {
    try {

      person.setNameTitleCode(record.get(Index.SALUTATION.ordinal()));
      // first name
      person.setFirstName(record.get(Index.FIRST_NAME.ordinal()));
      // last name
      person.setLastName(record.get(Index.LAST_NAME.ordinal()));

      // phone
      final String Phone1 = record.get(Index.PHONE_HOME.ordinal()).trim();
      person.setPhone01(Phone1.length() > 20 ? Phone1.substring(0, 20) : Phone1);
      final String Phone2 = (record.get(Index.PHONE_MOBILE.ordinal())).trim();
      person.setPhone02(Phone2.length() > 20 ? Phone2.substring(0, 20) : Phone2);
      final String Phone3 = record.get(Index.PHONE_BUSINESS.ordinal()).trim();
      final String businessPhone = (Phone3.length() > 20 ? Phone3.substring(0, 20) : Phone3);
      person.setPhone03(businessPhone);

      // email
      person.setEmail01(record.get(Index.EMAIL.ordinal()));

      person.setFieldB002(getSalesforceContactID(record));
      person.setFieldB003(getRecordID(record));

      person.setContactIndicator(true);

      person.setOrganization1Oid(mOrganization.getOid());

      mModelBroker.saveBeanForced(person);
    } catch (Exception e) {
      throw new Exception("Line " + lineNumber + '\n' + e);
    }

  }

  /**
   * Sets the address fields as given by the input.
   *
   * @param address
   * @param record
   */
  private void setAddressFields(final SisAddress address, final List<String> record, final int lineNumber) {
    address.setDistrictOid(getDistrict().getOid());
    String strAddressLine1 = record.get(Index.MAILING_STREET.ordinal());
    String strAddressLine2 = null;
    if (strAddressLine1.length() >= 100) {
      strAddressLine2 = strAddressLine1.substring(50, 99);
      strAddressLine1 = strAddressLine1.substring(0, 49);
    } else if (strAddressLine1.length() > 50) {
      strAddressLine2 = strAddressLine1.substring(50, strAddressLine1.length());
      strAddressLine1 = strAddressLine1.substring(0, 49);
    }
    strAddressLine1 = strAddressLine1.replace('|', ',');
    address.setAddressLine01(strAddressLine1);
    if (strAddressLine2 != null) {
      address.setAddressLine02(strAddressLine2);
    }
    final String strCity = record.get(Index.MAILING_CITY.ordinal());
    address.setCity(strCity);
    final String strState = record.get(Index.MAILING_STATE.ordinal());
    address.setState(strState);
    final String strPostalCode = record.get(Index.MAILING_POSTAL.ordinal());
    address.setPostalCode(strPostalCode);
    final String addressLine3 = strCity + (strState.length() > 0 ? ", " + strState : "") + (strPostalCode.length() > 0 ? ", " + strPostalCode : "");
    address.setAddressLine03(addressLine3);
    address.setCountry(record.get(Index.MAILING_COUNTRY.ordinal()));
    mModelBroker.saveBean(address);

    // get specific parent
//        if (record.get(Index.SF_CONTACT_ID.ordinal()).toString().equals("003A000000S0ymmIAB") ) {
//        	logInvalidRecord(lineNumber, "Address = " + strAddressLine1 + " | " + strAddressLine2 + " | " + addressLine3);
//        }

  }

  private void setStateSideSponsor(final SisStudent student, final List<String> record) {
    final String sss = student.getFieldB008();
    if (StringUtils.isNullOrEmpty(sss)) {
      // if parent's mailing address is not US, Required
      // else Not Required
      final String mailingCountry = record.get(Index.MAILING_COUNTRY.ordinal());
      if (!StringUtils.isEmpty(mailingCountry) && "US".equals(mailingCountry)) {
        student.setFieldB008("Not Required");
      } else if (!StringUtils.isEmpty(mailingCountry)) {
        student.setFieldB008("Required");
      }
    }
  }

  /**
   * *********************
   * String Methods    *
   * **********************
   */
  private String getSalesforceContactID(final List<String> record) {
    return record.get(Index.SF_CONTACT_ID.ordinal());
  }

  private String getRecordID(final List<String> record) {
    return record.get(Index.SF_RECORD_ID.ordinal());
  }

  private String getStudentRecordID(final List<String> record) {
    return record.get(Index.STUDENT_RECORD_ID.ordinal());
  }

  /**
   * *********************
   * Data Methods      *
   * **********************
   */
  private void populateStudentMap() {
    // find student contact record
    final Criteria criteria = new Criteria();
    criteria.addEqualTo(SisStudent.REL_SCHOOL + "." + SisSchool.COL_SCHOOL_ID, mSchool);
    final QueryByCriteria query = new QueryByCriteria(SisStudent.class, criteria);
    mStudents = mModelBroker.getMapByQuery(query, SisStudent.REL_PERSON + "." + SisPerson.COL_FIELD_B003, 6000);
  }

  private void populateParentMap() {
    final Criteria criteria = new Criteria();
    criteria.addEqualTo(SisPerson.COL_CONTACT_INDICATOR, true);
//    	logInvalidRecord(lineNumber, getSalesforceContactID(record).substring(0, 15));
    final QueryByCriteria query = new QueryByCriteria(SisPerson.class, criteria);
    mParents = mModelBroker.getMapByQuery(query, SisPerson.COL_FIELD_B002, 10000);
  }
}