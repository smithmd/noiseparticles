import com.follett.fsc.core.k12.beans.ReferenceCode;
import com.follett.fsc.core.k12.beans.StudentSchool;
import com.follett.fsc.core.k12.beans.X2BaseBean;
import com.follett.fsc.core.k12.business.*;
import com.follett.fsc.core.k12.tools.imports.TextImportJavaSource;
import com.follett.fsc.core.k12.web.AppGlobals;
import com.follett.fsc.core.k12.web.UserDataContainer;
import com.x2dev.sis.model.beans.*;
import com.x2dev.utils.StringUtils;
import com.x2dev.utils.X2BaseException;
import com.x2dev.utils.types.PlainDate;
import org.apache.ojb.broker.query.Criteria;
import org.apache.ojb.broker.query.QueryByCriteria;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

import static com.follett.fsc.core.k12.business.ModelProperty.PATH_DELIMITER;

/**
 * Java source for the student import for build conversion. Each
 * record has student, person, and enrollment components. This import
 * will create or update those three beans as necessary.
 *
 * @author X2 Development Corporation
 */
@SuppressWarnings({"serial", "deprecation", "unchecked"})
public class AcademyStudentImport extends TextImportJavaSource {
  /**
   * Name for the "enrollment status" report parameter. This value is a String.
   */
  private static final String PARAM_TEST = "isTest";
  private static final String PARAM_FILENAME = "filename";
  private static final String PARAM_USE_FTP = "useFtp";

  private static final String TRUE_INT = "1";
  private static final String FALSE_INT = "0";

  private static final String EMPTY = "";
  private static final String COMMA = ", ";

  // School IDs
  private static final String SCHOOL_ID_HISTORY = "HISTORY";
  private static final String SCHOOL_ID_NON_AFF = "NA";
  private static final String SCHOOL_ID_CAMP = "IAC";
  private static final String SCHOOL_ID_ACADEMY = "IAA";

  private static final String ENR_CANCELED = "Cancelled";
  private static final String ENR_COMMITTED = "Committed";
  private static final String ENR_PRE_REG = "Preregistered";

  private static final String EA_RECEIVED = "only ea received";
  private static final String EA_AND_DEPOSIT = "deposit paid & ea received";

  private static final int ADDRESS_FIELD_LENGTH = 50;
  private static final int PHONE_FIELD_LENGTH = 20;
  private static final int CONTACT_ID_LENGTH = 18;
  private static final int MAX_MAP_SIZE = 1000;
  private static final int SCHOOL_MAP_SIZE = 4;
  private static final int SENIOR_GRADE = 12;
  private static final int STATE_MAP_SIZE = 51;


  private PlainDate mDateStart;
  private PlainDate mDateEnd;

  /*
   * Input order
   */
  private enum Index {
    //62
    EXTERNAL_ID, APPLICATION_ID, SF_RECORD_ID, SF_CONTACT_ID, ACCOUNT_ID /*Used?*/,
    FIRST_NAME, LAST_NAME, MIDDLE_NAME, NICK_NAME, GENDER, ADMISSION_STATUS, // 11 (K)
    NEW_OR_RETURNING, TERM_SESSION_ID, CONDITIONAL_DECISION, OWNER_FULL_NAME,
    GRADE, YOG, ACADEMY_PROPOSED_MAJOR, ACADEMY_INSTRUMENT, PROPOSED_MAJOR,
    LIVING_STATUS_REQUESTED, EMAIL, PHONE_HOME, PHONE_MOBILE, //13 (X)
    BIRTHDAY, MAILING_STREET, MAILING_CITY, MAILING_STATE, MAILING_POSTAL_CODE,
    MAILING_COUNTRY, COUNTRY_OF_BIRTH, COUNTRY_OF_CITIZENSHIP, RELIGION, // 9 (AG)
    ETHNICITY, NAME_OF_SCHOOL, TELEPHONE_CURRENT_SCHOOL, FAX_CURRENT_SCHOOL,
    DATES_OF_ATTENDANCE, GRADES_COMPLETED, SCHOOL_ADDRESS, TYPE_OF_SCHOOL,
    SCHOOL_WEBSITE, ENGLISH_FIRST_LANGUAGE, ENGLISH_HOW_LONG_STUDIED, // 11 (AR)
    OTHER_LANGUAGES, CHRONIC_HEALTH_PROBLEMS, HAS_BEEN_SUSPENDED,
    CONSULTED_PROFESSIONAL, PROFESSIONALS_NAME, PROFESSIONALS_EMAIL,
    PROFESSIONALS_PHONE, PROFESSIONALS_FAX, PROFESSIONALS_ADDRESS, // 9 (BA)
    PROFESSIONALS_CITY, PROFESSIONALS_STATE, PROFESSIONALS_COUNTRY,
    PROFESSIONALS_POSTAL_CODE, PROFESSIONAL_TREATMENT_DATES,
    HEALTH_PROBLEM_DETAILS, ACADEMIC_DISCIPLINE_DETAILS, ASPEN_ID,
    AUP_SENT, Count  //7-1 (BJ)
  }

  private ModelBroker mModelBroker;
  private Map<String, SisSchool> mSchools;
  private boolean mIsTest;
  private Map<String, X2BaseBean> mStudents;
  private String mDistrictOid;

  private Map<String, ReferenceCode> mAcademicTracks = null;
  private Map<String, ReferenceCode> mAPStates = null;
  private Map<String, ReferenceCode> mAdmissionStatus = null;
  private Map<String, StudentProgramParticipation> mPrograms = null;
  private Map<String, StudentEnrollment> mEnrollments = null;

  protected final int getFieldCount() {
    return Index.Count.ordinal(); // <- probably a better way, but easier to
    // get with enum... less changes later
  }

  // entry point
  protected final void importData(final File sourceFile) throws Exception {

    // Setting the start date
    final String strStartDate = "06/15/" + Calendar.getInstance().get(Calendar.YEAR);
    final SimpleDateFormat formatter = new SimpleDateFormat("MM/dd/yyyy");
    formatter.setLenient(true);
    try {
      mDateStart = new PlainDate(formatter.parse(strStartDate).getTime());
    } catch (Exception e) {
      mDateStart = null;
    }

    // Setting the End date
    final String strEndDate = "06/14/" + (Calendar.getInstance().get(Calendar.YEAR) + 1);
    try {
      mDateEnd = new PlainDate(formatter.parse(strEndDate).getTime());
    } catch (Exception e) {
      mDateEnd = null;
    }

    setUseValueWrappers(true);

    final boolean useFtp = (Boolean) getParameter(PARAM_USE_FTP);

    File file = null;
    setValueWrapper('"');
    if (useFtp) {
      final String filename = (String) getParameter(PARAM_FILENAME);
      final File secureRoot = AppGlobals.getSecureRootDirectory(getOrganization());
      if (secureRoot != null) {
        file = new File(secureRoot.getAbsolutePath() + '/' + filename);
        final boolean readable = file.setReadable(true);

        if (!readable) {
          throw new Exception("Can't read from file: " + filename);
        }
      }
    }

    mModelBroker = new ModelBroker(getPrivilegeSet());
    mIsTest = (Boolean) getParameter(PARAM_TEST);
    mDistrictOid = getDistrict().getOid();

    AppGlobals.getLog().log(Level.INFO, "IAA Student Import - Start importing...");

    /*
     * Retrieve schools.
     */
    final Criteria schoolCriteria = new Criteria();

    final QueryByCriteria schoolQuery = new QueryByCriteria(SisSchool.class, schoolCriteria);
    mSchools = mModelBroker.getMapByQuery(schoolQuery, SisSchool.COL_SCHOOL_ID, SCHOOL_MAP_SIZE);

    populateStudentMap();

    populateEnrollmentMap();
    populateProgramsMap();

    if (useFtp) {
      super.importData(file);
    } else {
      super.importData(sourceFile);
    }
  }

  /**
   * @see com.follett.fsc.core.k12.tools.imports.TextImportJavaSource#importRecord(java.util.List, int)
   */
  protected final void importRecord(final List<String> record, final int lineNumber) throws Exception {
    // loops through the input CSV, one call to this method per row in the CSV

    // skips the header row
    if (lineNumber > 1) {
      /*
       * Check if the record represents a new student, create beans if necessary
       */
      final String recordId = getStudentRecordID(record);

      SisStudent student = (SisStudent) mStudents.get(recordId);

      // if student is not in the map, check the database one more time
      if (student == null) {
        student = getStudentByRecordId(recordId);
      }

      // if student is still not found, create a new one
      if (student == null) {
        createNewStudent(record, lineNumber);
        incrementInsertCount();
      } else {
        updateExistingStudent(record, lineNumber, student);
        incrementMatchCount();
        incrementUpdateCount();
      }
    } else {
      logInvalidRecord(lineNumber, "Skipping header row.");
    }
  }

  @Override
  protected final void saveState(final UserDataContainer userData) throws X2BaseException {
    setUseValueWrappers(false);
    super.saveState(userData);
  }

  /*
   * ***********************************
   * *****     STUDENT METHODS     *****
   * ***********************************
   */
  private void createNewStudent(final List<String> record, final int lineNumber) throws Exception {

    // create new beans
    final SisAddress address = (SisAddress) X2BaseBean.newInstance(
        SisAddress.class, getUser().getPersistenceKey());

    final SisPerson person = (SisPerson) X2BaseBean.newInstance(
        SisPerson.class, getUser().getPersistenceKey());

    final SisStudent student = (SisStudent) X2BaseBean.newInstance(
        SisStudent.class, getUser().getPersistenceKey());

    // create bean for health condition if 'details' field is not empty or professional consulted
    final String strHealthDetail = record.get(Index.HEALTH_PROBLEM_DETAILS.ordinal());
    final boolean boolProfCons = convertYesOrNoToBool(
        record.get(Index.CONSULTED_PROFESSIONAL.ordinal()));
    if (!StringUtils.isNullOrEmpty(strHealthDetail) || boolProfCons) {
      createHealthCondition(record, lineNumber, student);
    }

    // handle address data and attach to person
    setAddressFields(address, record);
    person.setMailingAddressOid(address.getOid());

    // handle the person data and save (student is a type of person, so need this to exist in DB first)
    setPersonFields(person, record);

    // set person oid on student, then add school/next school
    student.setPersonOid(person.getOid());
    student.setSchoolOid(mSchools.get(SCHOOL_ID_ACADEMY).getOid());
    student.setNextSchoolOid(mSchools.get(SCHOOL_ID_ACADEMY).getOid());
    // set student data and save
    setStudentFields(student, record, lineNumber);

    // create enrollment data and link to student
    setEnrollmentRecord(student, record, lineNumber);
    // create program data and link to student
    setProgramParticipation(student, record, lineNumber);
  }

  private void updateExistingStudent(final List<String> record,
        int lineNumber, SisStudent student) throws Exception {
    //

    final SisSchool sklIAA = mSchools.get(SCHOOL_ID_ACADEMY);
    final String iaaOid = getSchoolOid(SCHOOL_ID_ACADEMY);

    // we've found the school for the import
    if (sklIAA != null) {

      boolean wasCamper = getSchoolOid(SCHOOL_ID_CAMP).equals(student.getSchoolOid());

      logInvalidRecord(lineNumber, "\tFound school of " + student.getSchool().getName());

      final SisPerson person = student.getPerson();
      SisAddress address = (person.getMailingAddress());

      // if student address isn't found, create one
      if (address == null) {
        address = (SisAddress) X2BaseBean.newInstance(SisAddress.class, (getUser()).getPersistenceKey());
        person.setMailingAddressOid(address.getOid());
      }
      setAddressFields(address, record);

      // save any changes made to the person object
      setPersonFields(person, record);

      String admStatus = record.get(Index.ADMISSION_STATUS.ordinal());

      if (ENR_COMMITTED.equalsIgnoreCase(getEnrollmentStatusByAdmissionStatus(admStatus))
          || ENR_PRE_REG.equalsIgnoreCase(getEnrollmentStatusByAdmissionStatus(admStatus))
          ) {
        student.setSchoolOid(iaaOid);
        // set the next school to academy, because we always want this student in academy
        student.setNextSchoolOid(iaaOid);
      } else {
        if (!wasCamper) {
          student.setSchoolOid(getSchoolOid(SCHOOL_ID_NON_AFF));
        }
//        student.setNextSchoolOid(getSchoolOid(SCHOOL_ID_NON_AFF));
      }


      if (wasCamper && !student.getSchoolOid().equals(getSchoolOid(SCHOOL_ID_CAMP))) {
        setSecondarySchool(student, SCHOOL_ID_CAMP, lineNumber);
      }

      setStudentFields(student, record, lineNumber);

      // find the student's current health condition for this enrollment
      HealthCondition hcCurrentApp = getCurrentHealthCondition(student,
            getSchoolYear(record), getSchoolTerm(record));

      // if no health condition was found, then create a new one
      if (hcCurrentApp == null) {
        createHealthCondition(record, lineNumber, student);
      } else {
        setHealthConditionFields(hcCurrentApp, record, lineNumber);
      }

      // attempt to update the student's enrollment record
      setEnrollmentRecord(student, record, lineNumber);

      try {
        setProgramParticipation(student, record, lineNumber);
      } catch (Exception e) {
        throw new Exception("Line " + lineNumber + COMMA
            + record.get(Index.LAST_NAME.ordinal()) + "\n" + e);
      }
    }
  }

/* ************************************************************************** *
 * *****                         SETTER METHODS                         ***** *
 * ************************************************************************** */

  /**
   * Sets the address fields as given by the input.
   *
   * @param address
   * @param record
   */
  // Fields used: MAILING_STREET, MAILING_CITY, MAILING_STATE, MAILING_POSTAL_CODE, MAILING_COUNTRY
  private void setAddressFields(final SisAddress address, final List<String> record) {
    address.setDistrictOid(mDistrictOid);

    String strAddressLine1 = record.get(Index.MAILING_STREET.ordinal()).replace('|', ',');
    String strAddressLine2 = EMPTY;

    // truncate addresses that are too long for sql fields
    if (strAddressLine1.length() >= ADDRESS_FIELD_LENGTH * 2) {
      strAddressLine2 = strAddressLine1.substring(ADDRESS_FIELD_LENGTH, ADDRESS_FIELD_LENGTH * 2);
      strAddressLine1 = strAddressLine1.substring(0, ADDRESS_FIELD_LENGTH);
    } else if (strAddressLine1.length() > ADDRESS_FIELD_LENGTH) {
      strAddressLine2 = strAddressLine1.substring(ADDRESS_FIELD_LENGTH, strAddressLine1.length());
      strAddressLine1 = strAddressLine1.substring(0, ADDRESS_FIELD_LENGTH);
    }
    address.setAddressLine01(strAddressLine1);
    address.setAddressLine02(strAddressLine2);

    String strCity = record.get(Index.MAILING_CITY.ordinal());
    address.setCity(strCity);

    String strState = record.get(Index.MAILING_STATE.ordinal());
    address.setState(strState);

    String strPostalCode = record.get(Index.MAILING_POSTAL_CODE.ordinal());
    address.setPostalCode(strPostalCode);

    String addressLine3 = strCity
        + (strState.length() > 0 ? COMMA + strState : EMPTY)
        + (strPostalCode.length() > 0 ? COMMA + strPostalCode : EMPTY);
    address.setAddressLine03(addressLine3);
    address.setCountry(record.get(Index.MAILING_COUNTRY.ordinal()));

    // save AP State Abbreviation code
    address.setFieldValueByAlias("APState", getAPState(strState));

    mModelBroker.saveBeanForced(address);
  }

  private void createHealthCondition(final List<String> record, int lineNumber, SisStudent student) {

    final String strHealthDetail = record.get(Index.HEALTH_PROBLEM_DETAILS.ordinal());
    boolean boolProCons = convertYesOrNoToBool(record.get(Index.CONSULTED_PROFESSIONAL.ordinal()));
    if (!StringUtils.isNullOrEmpty(strHealthDetail) || boolProCons) {
      HealthCondition hcCurrentApp =
        (HealthCondition) X2BaseBean.newInstance(HealthCondition.class, getUser().getPersistenceKey());
      hcCurrentApp.setStudentOid(student.getOid());
      hcCurrentApp.setFieldA003(getSchoolYear(record));
      hcCurrentApp.setFieldA004(getSchoolTerm(record));
      hcCurrentApp.setFieldA002(TRUE_INT);
      setHealthConditionFields(hcCurrentApp, record, lineNumber);
    }
  }

  /**
   * Sets the medical fields as given by the input.
   *
   * @param health
   * @param record
   */
  private void setHealthConditionFields(final HealthCondition health,
                                        final List<String> record, int lineNumber) {
    // TODO: figure out a way to simplify this method

    if (health != null) {
      String appId = record.get(Index.APPLICATION_ID.ordinal());
      if (StringUtils.isNullOrEmpty(health.getFieldB001())) {
        health.setFieldB001(appId);
      } else if (!StringUtils.isNullOrEmpty(health.getFieldB001())
                 && !health.getFieldB001().equals(appId)) {
        health.setFieldB001(appId);
      }

      String healthDetails = record.get(Index.HEALTH_PROBLEM_DETAILS.ordinal());

      String strTreatmentDates = record.get(Index.PROFESSIONAL_TREATMENT_DATES.ordinal());
      if (strTreatmentDates.length() > 0) {
        strTreatmentDates = "Professional Treatment Dates: " + strTreatmentDates + "\n\n";
        String comment = StringUtils.removeHtml(strTreatmentDates
                                                + healthDetails.replace('|', ','), false, false);
        if (StringUtils.isNullOrEmpty(health.getComment())) {
          health.setComment(comment);
        } else if (!StringUtils.isNullOrEmpty(health.getComment())
                   && !health.getComment().equals(comment)) {
          health.setComment(comment);
        }
      } else {
        String comment = healthDetails.replace('|', ',');
        if (StringUtils.isNullOrEmpty(health.getComment())) {
          health.setComment(comment);
        } else if (!StringUtils.isNullOrEmpty(health.getComment())
                   && !health.getComment().equals(comment)) {
          health.setComment(comment);
        }
      }

      String profConsult = convertYesOrNoToStr(record.get(Index.CONSULTED_PROFESSIONAL.ordinal()));
      if (StringUtils.isNullOrEmpty(health.getFieldA005()) ||
          !health.getFieldA005().equals(profConsult)) {
        health.setFieldA005(profConsult);
      }

      // health professional information		ENR_FIELDD_001
      StringBuilder doctor = new StringBuilder();
      doctor.append(record.get(Index.PROFESSIONALS_NAME.ordinal())).append('\n');
      doctor.append(record.get(Index.PROFESSIONALS_ADDRESS.ordinal())).append('\n');
      doctor.append(record.get(Index.PROFESSIONALS_CITY.ordinal()));
      doctor.append(COMMA).append(record.get(Index.PROFESSIONALS_STATE.ordinal()));
      doctor.append(' ').append(record.get(Index.PROFESSIONALS_POSTAL_CODE.ordinal()));
      doctor.append('\n');
      doctor.append(record.get(Index.PROFESSIONALS_COUNTRY.ordinal())).append('\n');
      doctor.append("Email: ").append(record.get(Index.PROFESSIONALS_EMAIL.ordinal())).append('\n');
      doctor.append("Phone: ").append(record.get(Index.PROFESSIONALS_PHONE.ordinal())).append('\n');
      doctor.append("Fax: ").append(record.get(Index.PROFESSIONALS_FAX.ordinal())).append('\n');

      //logInvalidRecord(1, doctor.toString());
      String strDoctor = doctor.toString().replace('|', ',');
      if (StringUtils.isNullOrEmpty(health.getFieldD001())) {
        health.setFieldD001(strDoctor);
      } else if (!StringUtils.isNullOrEmpty(health.getFieldD001())
            && health.getFieldD001().equals(strDoctor)) {
        health.setFieldD001(strDoctor);
      }

      if (health.isDirty()) {
        List<ValidationError> errors = mModelBroker.saveBean(health);
        for (ValidationError ve : errors) {
          logInvalidRecord(lineNumber, ve.toString());
        }
      }
    }
  }

  /**
   * Sets the person fields as given by the input.
   *
   * @param person
   * @param record
   */
  private void setPersonFields(final SisPerson person, final List<String> record) {
    if (person != null) {
      person.setDistrictOid(mDistrictOid);

      String psnID = person.getPersonId();
      if (StringUtils.isNullOrEmpty(psnID)) {
        person.setPersonId(getIDNumber(record));
      }

      String dateOfBirth = record.get(Index.BIRTHDAY.ordinal());

      try {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        formatter.setLenient(true);
        java.sql.Date date = new java.sql.Date(formatter.parse(dateOfBirth).getTime());

        //Log things....
        AppGlobals.getLog().log(Level.INFO, "IAA Student Import with Medical - DOB:"
                                + dateOfBirth + " from PlainDate:"
                                + (new com.x2dev.utils.types.PlainDate(date)).toString());
        //which isn't working... so also throw exception
        //throw new Exception("DOB: " + DOB + " from PlainDate:"
        //                    + (new com.x2dev.utils.types.PlainDate(date)).toString());

        person.setDob(new com.x2dev.utils.types.PlainDate(date));
      } catch (Exception e) {
        AppGlobals.getLog().log(Level.INFO, "IAA Student Import with Medical - DOB:"
                                + dateOfBirth + " with Exception: " + e.getMessage());
      }

      person.setFirstName(record.get(Index.FIRST_NAME.ordinal()));
      String gender = record.get(Index.GENDER.ordinal());
      if (gender.length() > 0) {
        person.setGenderCode(gender.substring(0, 1).toUpperCase());
      }
      person.setLastName(record.get(Index.LAST_NAME.ordinal()));
      person.setMiddleName(record.get(Index.MIDDLE_NAME.ordinal()));
      person.setStudentIndicator(true);
      //set nick name
      person.setFieldB001(record.get(Index.NICK_NAME.ordinal()));

      // email01 stays blank for mass update depending on time of year

      String email = record.get(Index.EMAIL.ordinal());
      if (email != null && email.length() > 0) {
        email = email.toLowerCase();

        if (email.endsWith("interlochen.org")) {
          person.setEmail02(email);
          // email02 becomes populated if interlochen, else leave it alone
        } else {
          // PSN_FIELDC_004 (email 3) gets personal email if not interlochen address
          person.setFieldC004(email);
        }
      }

      String strHomePhone = record.get(Index.PHONE_HOME.ordinal());
      if (strHomePhone.length() <= PHONE_FIELD_LENGTH) {
        person.setPhone01(strHomePhone);
      }

      String strMobilePhone = record.get(Index.PHONE_MOBILE.ordinal());
      if (strMobilePhone.length() <= PHONE_FIELD_LENGTH) {
        person.setPhone02(strMobilePhone);
      }

      // set Salesforce Contact ID
      String contactId = getStudentContactID(record);
      if (contactId.length() == CONTACT_ID_LENGTH) {
        person.setFieldB002(contactId);
      }
      // set Salesforce Record ID
      person.setFieldB003(record.get(Index.SF_RECORD_ID.ordinal()));

      // set country of citizenship
      person.setFieldC002(record.get(Index.COUNTRY_OF_CITIZENSHIP.ordinal()));

      //set test flag
      String test;
      if (mIsTest) {
        test = TRUE_INT;
      } else {
        test = FALSE_INT;
      }
      person.setFieldA001(test);

      mModelBroker.saveBeanForced(person);
    }
  }

  /**
   * Sets the student fields as given by the input.
   *
   * @param student
   * @param record
   */
  private void setStudentFields(SisStudent student, List<String> record, int lineNumber) {
    // TODO: This method is probably too long... look into shortening it

    if (student != null) {
      logInvalidRecord(lineNumber, "Processing student: " + student.getPerson().getLastName()
          + COMMA + student.getPerson().getFirstName() + " (" + student.getEnrollmentStatus() + ")");

      student.setDistrictOid(mDistrictOid);

      String yog = record.get(Index.YOG.ordinal()).trim();

      if (!StringUtils.isEmpty(yog) && StringUtils.isInteger(yog)) {
        student.setYog(Integer.parseInt(yog));

        student.setGradeLevel(getGradeLevelFromYog(yog));
      }

      // External ID
      student.setFieldA002(record.get(Index.EXTERNAL_ID.ordinal()));

      String cal = student.getCalendarCode();
      if (StringUtils.isEmpty(cal)) {
        student.setCalendarCode("Standard");
      }


      /** TODO:
       *
       * THE FOLLOWING FIELDS MAY JUST HAVE THEIR VALUES TOGGLED FOR RETURNING STUDENTS.
       *
       * THEY SHOULD BE RE-EVALUATED. WE PROBABLY DON'T WANT TO PUT THIS ON STUDENT AT ALL.
       *
       */

      // don't set admission status on student because of multiple rows just toggling the value
      //       student.setFieldC001(getStudentAdmissionStatus(record));

      String instrument = record.get(Index.ACADEMY_INSTRUMENT.ordinal());
      String major = record.get(Index.ACADEMY_PROPOSED_MAJOR.ordinal());
      String academicTrack = getAcademicTrackByMajorAndInstrument(major, instrument);
      logInvalidRecord(lineNumber, "Academic Track: " + academicTrack +
          " From: " + major + "/" + instrument);
      student.setAcademicTrackType(academicTrack);

      //set AUP
      boolean aupIsComplete = record.get(Index.AUP_SENT.ordinal()).trim().equalsIgnoreCase("complete");
      if (aupIsComplete) {
        student.setFieldB005(TRUE_INT);
      }

      // parent permission
      if (StringUtils.isNullOrEmpty(student.getFieldB003())) {
        student.setFieldB003(FALSE_INT);
      }

      String conditionalDecision = record.get(Index.CONDITIONAL_DECISION.ordinal());
      conditionalDecision = conditionalDecision.toLowerCase();
      if (conditionalDecision.contains("academic")) {
        logInvalidRecord(lineNumber, "\tFound Academic Condition");
        student.setFieldA015(TRUE_INT);
      } else {
        student.setFieldA015(FALSE_INT);
      }

      if (conditionalDecision.contains("mental")) {
        logInvalidRecord(lineNumber, "\tFound Mental Condition");
        student.setFieldA014(TRUE_INT);
      } else {
        student.setFieldA014(FALSE_INT);
      }

      if (conditionalDecision.contains("behavior")) {
        logInvalidRecord(lineNumber, "\tFound Behavior Condition");
        student.setFieldA040(TRUE_INT);
      } else {
        student.setFieldA040(FALSE_INT);
      }

      if (conditionalDecision.contains("physical")) {
        logInvalidRecord(lineNumber, "\tFound Physical Condition");
        student.setFieldA041(TRUE_INT);
      } else {
        student.setFieldA041(FALSE_INT);
      }

      /** END OF FIELDS THAT MIGHT TOGGLE VALUES **/

      logInvalidRecord(lineNumber, "\tSaving student: " + student.getNameView());
      List<ValidationError> errors = mModelBroker.saveBean(student);
      if (errors.size() > 0) {
        logInvalidRecord(lineNumber, "\t*** Errors when saving student ***");
        for (ValidationError ve : errors) {
          logInvalidRecord(lineNumber, "\t\t" + ve.toString());
        }
      }
    }
  }

  private void setSecondarySchool(SisStudent student, String schoolId, int lineNumber) {
    boolean schoolExists = false;
    Collection<StudentSchool> schools = student.getStudentSchools();

    for (StudentSchool s : schools) {
      if (s.getSchoolOid().equalsIgnoreCase(getSchoolOid(schoolId))) {
        logInvalidRecord(lineNumber, "Found school in secondary schools: " + s.getSchool().getSchoolId());
        schoolExists = true;
        break;
      }
    }

    if (!schoolExists) {
      StudentSchool secondarySchool =
              (StudentSchool) X2BaseBean.newInstance(StudentSchool.class, getUser().getPersistenceKey());

      secondarySchool.setDistrictContextOid(getDistrict().getCurrentContextOid());
      //noinspection deprecation
      secondarySchool.setDistrictOid(mDistrictOid);
      secondarySchool.setSchoolOid(getSchoolOid(schoolId));
      secondarySchool.setStudentOid(student.getOid());
      secondarySchool.setType(StudentSchool.SECONDARY);

      mModelBroker.saveBeanForced(secondarySchool);
    }
  }

  private String getGradeLevelFromYog(String yog) {
    String gradeLevel = null;
    PreferenceSet orgPrefSet = PreferenceManager.getPreferenceSet(getOrganization());
    if (orgPrefSet != null) {
      final String ageOutYog = orgPrefSet.getPreferenceValue(SisPreferenceConstants.SYS_STD_AGEOUTYOG);
      if (StringUtils.isNumeric(yog) && !yog.equals(ageOutYog)) {
        gradeLevel = String.format("%02d", (SENIOR_GRADE + getOrganization().getCurrentContext().getSchoolYear()) - Integer.parseInt(yog));
      } else if (StringUtils.isNumeric(yog) && yog.equals(ageOutYog)) {
        gradeLevel = orgPrefSet.getPreferenceValue(SisPreferenceConstants.SYS_STD_AGEOUTGRADECODE);
      }
    }

    return gradeLevel;
  }

  private void setEnrollmentRecord(SisStudent student, List<String> record, int lineNumber) {

    StudentEnrollment currentEnrollment = mEnrollments.get(record.get(Index.APPLICATION_ID.ordinal()).trim());

    // couldn't find the current enrollment data, so create one
    if (currentEnrollment == null) {
      currentEnrollment = (StudentEnrollment) X2BaseBean.newInstance(StudentEnrollment.class, getUser().getPersistenceKey());
      currentEnrollment.setStudentOid(student.getOid());
    }

    currentEnrollment.setSchoolOid(getSchoolOid(SCHOOL_ID_ACADEMY));

    // set remaining fields and save bean
    setEnrollmentRecordFields(currentEnrollment, record, lineNumber);
  }

  private void setEnrollmentRecordFields(final StudentEnrollment enrollment, final List<String> record, final int lineNumber) {
    // TODO: figure out a way to simplify this method

    // major			ENR_FIELDC_003
    String major = record.get(Index.ACADEMY_PROPOSED_MAJOR.ordinal());
    if (!StringUtils.isEmpty(major)) {
      enrollment.setFieldC003(major.trim());
    }

    // admission status		ENR_FIELDC_001
    String admissionStatus = getStudentAdmissionStatus(record);
    enrollment.setFieldC001(admissionStatus.trim());

    String oldEnrollmentStatus = enrollment.getStatusCode();
    if (StringUtils.isNullOrEmpty(oldEnrollmentStatus)) {
      oldEnrollmentStatus = EMPTY;
    } else {
      oldEnrollmentStatus = oldEnrollmentStatus.trim();
    }

    setEnrollmentStatus(oldEnrollmentStatus, admissionStatus, enrollment);

    // new / returning		ENR_FIELDB_007
    String studentStatus = record.get(Index.NEW_OR_RETURNING.ordinal());
    if (!StringUtils.isEmpty(studentStatus)) {
      if (studentStatus.startsWith("Former")) {
        studentStatus = "Returning";
      }
      enrollment.setFieldB007(studentStatus.trim());
    }

    // app id			ENR_FIELDB_002
    String appId = record.get(Index.APPLICATION_ID.ordinal());
    if (!StringUtils.isEmpty(appId)) {
      enrollment.setFieldB002(appId.trim());
    }

    enrollment.setFieldA004(getGradeLevel(record));

    // instrument		ENR_FIELDB_001
    String instrument = record.get(Index.ACADEMY_INSTRUMENT.ordinal());
    if (!StringUtils.isEmpty(instrument)) {
      if (instrument.contains("Singer-Songw")) {
        enrollment.setFieldB001("Singer-Songwriter");
      } else {
        enrollment.setFieldB001(instrument.trim());
      }
    }

    // is english your first language?
    // esl student		ENR_FIELDA_001
    String esl = record.get(Index.ENGLISH_FIRST_LANGUAGE.ordinal());
    if (!StringUtils.isEmpty(esl) && esl.equalsIgnoreCase("no")) {
      enrollment.setFieldA001(TRUE_INT);
    } else {
      enrollment.setFieldA001(FALSE_INT);
    }

    // International student flag	ENR_FIELDA_008
    String country = record.get(Index.COUNTRY_OF_CITIZENSHIP.ordinal());
    if (country != null) {
      if (country.trim().equals("US")) {
        enrollment.setFieldA008(FALSE_INT);
      } else {
        enrollment.setFieldA008(TRUE_INT);
      }
    }

    // living status	ENR_FIELDA_003
    String type = record.get(Index.LIVING_STATUS_REQUESTED.ordinal());
    if (!StringUtils.isEmpty(type)) {
      type = type.substring(0, type.indexOf(' ')).trim();
      enrollment.setFieldA003(StringUtils.capitalize(type));
    }

    // enrollment officer	ENR_FIELDC_002
    String officer = record.get(Index.OWNER_FULL_NAME.ordinal());
    if (!StringUtils.isEmpty(officer)) {
      enrollment.setFieldC002(officer.trim());
    }

    String year = getSchoolYear(record);
    if (!StringUtils.isEmpty(year)) {
      enrollment.setFieldA006(year);
    }

    // term				ENR_FIELDB_010
    String term = getSchoolTerm(record);
    if (!StringUtils.isEmpty(term)) {
      enrollment.setFieldB010(term);
    }

    // YOG				ENR_YOG     <-- previously on student record
    String yog = record.get(Index.YOG.ordinal()).trim();
    if (!StringUtils.isEmpty(yog) && StringUtils.isInteger(yog)) {
      enrollment.setYog(Integer.parseInt(yog));
    }

    // save bean
    try {
      List<ValidationError> errors = mModelBroker.saveBean(enrollment);
      for (ValidationError ve : errors) {
        logInvalidRecord(lineNumber, ve.toString());
      }
    } catch (InsufficientPrivilegesException e) {
      logInvalidRecord(lineNumber, "Couldn't save enrollment. Insufficient Privileges.");
    }
  }

  private void setEnrollmentStatus(String oldEnrollmentStatus, String admissionStatus, StudentEnrollment enrollment) {

    if (!"Active".equals(oldEnrollmentStatus) && !"Inactive".equals(oldEnrollmentStatus) && !"Graduated".equals(oldEnrollmentStatus)) {
      // student status	ENR_ENROLLMENT_STATUS_CODE
      String enrollmentStatus = getEnrollmentStatusByAdmissionStatus(admissionStatus);

      boolean isEAComplete = false;
      if (EA_RECEIVED.equalsIgnoreCase(admissionStatus) ||
          EA_AND_DEPOSIT.equalsIgnoreCase(admissionStatus)) {
        isEAComplete = true;
      }

      if (!oldEnrollmentStatus.equals(enrollmentStatus)) {
        //ENR_ENROLLMENT_DATE
        enrollment.setEnrollmentDate(new com.x2dev.utils.types.PlainDate());

        enrollment.setStatusCode(enrollmentStatus);
      }

      // EA Received flag set		ENR_FIELDB_003
      if (isEAComplete) {
        enrollment.setFieldB003(TRUE_INT);
      } else {
        enrollment.setFieldB003(FALSE_INT);
      }
    }
  }

  private void setProgramParticipation(SisStudent student, List<String> record, int lineNumber) {

    StudentProgramParticipation currentProgram = mPrograms.get(record.get(Index.APPLICATION_ID.ordinal()).trim());

    if (currentProgram == null) {
      currentProgram = (StudentProgramParticipation) X2BaseBean.newInstance(StudentProgramParticipation.class, getUser().getPersistenceKey());
      currentProgram.setStudentOid(student.getOid());
      currentProgram.setFieldA011(SCHOOL_ID_ACADEMY); // sets the school ID
    }
    setProgramParticipationFields(currentProgram, record, lineNumber);
  }

  private void setProgramParticipationFields(StudentProgramParticipation program, List<String> record, int lineNumber) {
    // TODO: Figure out a way to simplify this method

    // admission status
    program.setFieldC001(record.get(Index.ADMISSION_STATUS.ordinal()));

    // app id
    program.setFieldB002(record.get(Index.APPLICATION_ID.ordinal()));

    // Enrollment Agreement
    String admissionStatus = getStudentAdmissionStatus(record);
    boolean isEAComplete = false;
    if (EA_RECEIVED.equalsIgnoreCase(admissionStatus) ||
        EA_AND_DEPOSIT.equalsIgnoreCase(admissionStatus)) {
      isEAComplete = true;
    }

    // EA Received flag set		PGM_FIELDA_002
    if (isEAComplete) {
      program.setFieldA002(TRUE_INT);
    } else {
      program.setFieldA002(FALSE_INT);
    }

    // Start and End dates
    program.setStartDate(mDateStart);
    program.setEndDate(mDateEnd);

    // instrument   PGM_FIELDB_008
    program.setFieldB008(record.get(Index.ACADEMY_INSTRUMENT.ordinal()));

    // International Student    PGM_FIELDA_007
    String country = record.get(Index.COUNTRY_OF_CITIZENSHIP.ordinal());
    if (country != null) {
      if (country.trim().equals("US")) {
        program.setFieldA007(FALSE_INT);
      } else {
        program.setFieldA007(TRUE_INT);
      }
    }

    // Living Status      PGM_FIELDA_008
    String type = record.get(Index.LIVING_STATUS_REQUESTED.ordinal());
    if (!StringUtils.isEmpty(type)) {
      type = type.substring(0, type.indexOf(' ')).trim();
      program.setFieldA008(StringUtils.capitalize(type));
    }

    // Major    PGM_FIELDC_005
    String major = record.get(Index.ACADEMY_PROPOSED_MAJOR.ordinal());
    if (!StringUtils.isEmpty(major)) {
      program.setFieldC005(major);
    }

    // new / returning		PGM_FIELDA_009
    String studentStatus = record.get(Index.NEW_OR_RETURNING.ordinal());
    if (!StringUtils.isEmpty(studentStatus)) {
      if (studentStatus.startsWith("Former")) {
        studentStatus = "Returning";
      }
      program.setFieldA009(studentStatus.trim());
    }

    // year       PGM_FIELDA_010
    String year = getSchoolYear(record);
    if (!StringUtils.isEmpty(year)) {
      program.setFieldA010(year);
    }

    // term				PGM_FIELDB_009
    String term = getSchoolTerm(record);
    if (!StringUtils.isEmpty(term)) {
      program.setFieldB009(term);
    }

    if (program.isDirty()) {
      try {
        List<ValidationError> errors = mModelBroker.saveBean(program);

        for (ValidationError ve : errors) {
          logInvalidRecord(lineNumber, ve.toString());
        }
      } catch (InsufficientPrivilegesException e) {
        logInvalidRecord(lineNumber, "Couldn't save program. Insufficient Privileges.");
      }
    }
  }

  /*
   * ************************************************************************** *
   * **********                    GETTER METHODS                    ********** *
   * ************************************************************************** *
   */

  private String getGradeLevel(final List<String> record) {
    // grade level		ENR_FIELDA_004
    String grade = record.get(Index.GRADE.ordinal());
    if (!StringUtils.isNullOrEmpty(grade)) {
      if (grade.toLowerCase().startsWith("post")) {
        grade = "13";
      } else {
        grade = grade.toLowerCase().substring(0, grade.indexOf('t')).trim();
      }
      grade = StringUtils.padLeft(grade, 2, '0');
    }

    return grade;
  }

  private String getAcademicTrackByMajorAndInstrument(final String major, final String instrument) {
    StringBuilder trackDesc = new StringBuilder(major);
    if ("Music".equals(major)) {
      trackDesc.append(" - ");
      if ("Guitar".equals(instrument)
          || "Composition".equals(instrument)
          || "Organ".equals(instrument)
          || "Piano".equals(instrument)
          ) {
        trackDesc.append(instrument);
      } else if (instrument.contains("Voice")) {
        trackDesc.append("Vocal");
      } else if ("Singer-Songwriter".equalsIgnoreCase(instrument)) {
        trackDesc.append("Sing/Song");
      } else {
        trackDesc.append("Orch. Instrument");
      }
    }

    return getAcademicTrackCode(trackDesc.toString());
  }

  private String getAcademicTrackCode(String description) {
    String trackCode;

    if (mAcademicTracks == null) {
      Criteria criteria = new Criteria();
      criteria.addEqualTo(ReferenceCode.REL_REFERENCE_TABLE + PATH_DELIMITER + SisReferenceTable.COL_USER_NAME, "Academic Track Type");
      QueryByCriteria query = new QueryByCriteria(ReferenceCode.class, criteria);
      mAcademicTracks = getBroker().getMapByQuery(query, ReferenceCode.COL_DESCRIPTION, 15);
    }

    ReferenceCode refCode = mAcademicTracks.get(description);
    if (refCode == null) {
      logInvalidRecord(1, description);
      trackCode = EMPTY;
    } else {
      trackCode = refCode.getCode();
    }

    return trackCode;
  }

  private String getAPState(String stateCode) {
    String apState;

    if (mAPStates == null) {
      Criteria criteria = new Criteria();
      criteria.addEqualTo(ReferenceCode.REL_REFERENCE_TABLE + PATH_DELIMITER + SisReferenceTable.COL_USER_NAME, "Associated Press State Codes");
      QueryByCriteria query = new QueryByCriteria(ReferenceCode.class, criteria);
      mAPStates = getBroker().getMapByQuery(query, ReferenceCode.COL_CODE, STATE_MAP_SIZE);
    }

    ReferenceCode refCode = mAPStates.get(stateCode);
    if (refCode == null) {
      logInvalidRecord(1, "Bad AP State Code lookup: " + stateCode);
      apState = EMPTY;
    } else {
      apState = refCode.getDescription();
    }

    return apState;
  }

  private String getEnrollmentStatusByAdmissionStatus(String admStatus) {
    String status;

    if (mAdmissionStatus == null) {
      Criteria criteria = new Criteria();
      criteria.addEqualTo(ReferenceCode.REL_REFERENCE_TABLE + PATH_DELIMITER + SisReferenceTable.COL_USER_NAME, "Admission-Enrollment Status");
      QueryByCriteria query = new QueryByCriteria(ReferenceCode.class, criteria);
      mAdmissionStatus = getBroker().getMapByQuery(query, ReferenceCode.COL_CODE, 25);
    }

    ReferenceCode refCode = mAdmissionStatus.get(admStatus);
    if (refCode == null) {
      logInvalidRecord(1, "Bad Enrollment Code lookup: " + admStatus);
      status = EMPTY;
    } else {
      status = refCode.getDescription();
    }
    return status;
  }

  private HealthCondition getCurrentHealthCondition(final SisStudent student, String year, String term) {
    final Criteria criteria = new Criteria();
    criteria.addEqualTo(HealthCondition.COL_FIELD_A002, TRUE_INT);
    criteria.addEqualTo(HealthCondition.REL_STUDENT, student.getOid());
    criteria.addEqualTo(HealthCondition.COL_FIELD_A003, year);
    criteria.addEqualTo(HealthCondition.COL_FIELD_A004, term);
    final QueryByCriteria query = new QueryByCriteria(HealthCondition.class, criteria);

    return (HealthCondition) mModelBroker.getBeanByQuery(query);
  }

  private String getSchoolOid(String schoolId) {
    SisSchool school = mSchools.get(schoolId);
    if (school == null) {
      return "";
    } else {
      return school.getOid();
    }
  }

  private String getStudentContactID(final List<String> record) {
    String contactID = record.get(Index.SF_CONTACT_ID.ordinal());

    return contactID.trim();
  }

  private String getStudentRecordID(final List<String> record) {
    String recordId = record.get(Index.SF_RECORD_ID.ordinal());

    return recordId.trim();
  }

  private String getStudentAdmissionStatus(final List<String> record) {
    String admissionStatus = record.get(Index.ADMISSION_STATUS.ordinal());

    return admissionStatus.trim();
  }

  private String getSchoolYear(List<String> record) {
    final String termSessionId = record.get(Index.TERM_SESSION_ID.ordinal());
    return termSessionId.substring(termSessionId.indexOf(' ')).trim();
  }

  private String getSchoolTerm(List<String> record) {
    final String termSessionId = record.get(Index.TERM_SESSION_ID.ordinal());
    return termSessionId.substring(0, termSessionId.indexOf(' ')).trim();
  }

  private SisStudent getStudentByRecordId(String recordId) {
    final Criteria criteria = new Criteria();
    criteria.addEqualTo(SisStudent.REL_PERSON + PATH_DELIMITER + SisPerson.COL_FIELD_B003, recordId);
    final QueryByCriteria query = new QueryByCriteria(SisStudent.class, criteria);

    return (SisStudent) getBroker().getBeanByQuery(query);
  }

  private String getIDNumber(List<String> record) {
    String recordId = record.get(Index.SF_RECORD_ID.ordinal());

    return recordId.substring(4);
  }

  /*
   * ************************************************************** *
   * *****                 CONVERSION METHODS                 ***** *
   * ************************************************************** *
   */

  private String convertYesOrNoToStr(String yesOrNo) {
    String num;

    if (yesOrNo.equalsIgnoreCase("yes")) {
      num = TRUE_INT;
    } else {
      num = FALSE_INT;
    }

    return num;
  }

  private boolean convertYesOrNoToBool(String yesOrNo) {
    return yesOrNo.equalsIgnoreCase("yes");
  }

  /*
   * ************************************************************** *
   * *****                    DATA METHODS                    ***** *
   * ************************************************************** *
   */

  private void populateStudentMap() throws Exception {
    // find student contact record
    final Criteria criteria = new Criteria();
    criteria.addEqualTo(SisStudent.REL_SCHOOL + PATH_DELIMITER + SisSchool.COL_SCHOOL_ID, SCHOOL_ID_ACADEMY);
    criteria.addEqualTo(SisStudent.REL_PERSON + PATH_DELIMITER + SisPerson.COL_FIELD_A001, (mIsTest ? TRUE_INT : FALSE_INT));
    final QueryByCriteria query = new QueryByCriteria(SisStudent.class, criteria);
    mStudents = mModelBroker.getMapByQuery(query, SisStudent.REL_PERSON + PATH_DELIMITER + SisPerson.COL_FIELD_B003, MAX_MAP_SIZE);

    if (mStudents.isEmpty()) {
      throw new Exception("Student map empty...");
    }
  }

  private void populateEnrollmentMap() {
    final Collection<String> years = new ArrayList<String>();
    final int year = Calendar.getInstance().get(Calendar.YEAR);
    years.add(Integer.toString(year - 1));
    years.add(Integer.toString(year));
    years.add(Integer.toString(year + 1));

    Criteria c = new Criteria();
    c.addIn(StudentEnrollment.COL_FIELD_A006, years);
    c.addEqualTo(StudentEnrollment.REL_SCHOOL + PATH_DELIMITER + SisSchool.COL_SCHOOL_ID, SCHOOL_ID_ACADEMY);
    QueryByCriteria query = new QueryByCriteria(StudentEnrollment.class, c);
    mEnrollments = getBroker().getMapByQuery(query, StudentEnrollment.COL_FIELD_B002, MAX_MAP_SIZE);

    logInvalidRecord(0, "Found " + mEnrollments.size() + " Student Enrollment records for this year");
  }

  private void populateProgramsMap() {
    final Collection<String> years = new ArrayList<String>();
    final int year = Calendar.getInstance().get(Calendar.YEAR);
    years.add(Integer.toString(year - 1));
    years.add(Integer.toString(year));
    years.add(Integer.toString(year + 1));

    Criteria c = new Criteria();
    c.addIn(StudentProgramParticipation.COL_FIELD_A010, years);
    c.addEqualTo(StudentProgramParticipation.COL_FIELD_A011, SCHOOL_ID_ACADEMY);
    QueryByCriteria query = new QueryByCriteria(StudentProgramParticipation.class, c);
    mPrograms = getBroker().getMapByQuery(query, StudentProgramParticipation.COL_FIELD_B002, MAX_MAP_SIZE);

    logInvalidRecord(0, "Found " + mPrograms.size() + " Program Participation records for this year");
  }
}
