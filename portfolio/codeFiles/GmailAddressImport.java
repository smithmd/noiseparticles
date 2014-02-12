import java.io.File;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.apache.ojb.broker.query.Criteria;
import org.apache.ojb.broker.query.QueryByCriteria;

import com.follett.fsc.core.k12.beans.X2BaseBean;
import com.follett.fsc.core.k12.tools.imports.TextImportJavaSource;
import com.follett.fsc.core.k12.web.AppGlobals;
import com.follett.fsc.core.k12.web.UserDataContainer;
import com.x2dev.sis.model.beans.SisOrganization;
import com.x2dev.sis.model.beans.SisPerson;
import com.x2dev.sis.model.beans.SisSchool;
import com.x2dev.sis.model.beans.SisStudent;
import com.x2dev.utils.X2BaseException;

@SuppressWarnings("serial")
public class GmailAddressImport extends TextImportJavaSource {

  private Map<String, X2BaseBean> mStudents;
  private String mSchoolId;

  private static final String PARAM_FILENAME = "filename";
  private static final String PARAM_USE_FTP = "useFtp";
  private static final String PARAM_SCHOOL_ID = "school";

  // Enum that contains the names, in order, of the columns in the csv being imported
  private enum Index {
    PASSWORD, PERSON_ID, PERSONAL_EMAIL, DATE, EMAIL, IS_DUPLICATE, Count
  }

  protected int getFieldCount() {
    return Index.Count.ordinal();
  }

  /**
   * @see com.follett.fsc.core.k12.tools.imports.TextImportJavaSource#importData(java.io.File)
   *      <p/>
   *      Entry Point for data file
   */
  protected void importData(final File sourceFile) throws Exception {
    AppGlobals.getLog().log(Level.INFO, "IAA Gmail Address import starting...");

    mSchoolId = (String) getParameter(PARAM_SCHOOL_ID);

    populateStudentMap();

    final boolean useFtp = (Boolean) getParameter(PARAM_USE_FTP);

    // using double quotes to wrap values in csv
    setUseValueWrappers(false);
//    	setValueWrapper('"');

    if (useFtp) {
      File file;
      final String filename = (String) getParameter(PARAM_FILENAME);
      final File secureRoot = AppGlobals.getSecureRootDirectory(getOrganization());
      if (secureRoot == null) {
        throw new Exception("Can't get SecureRoot");
      }

      file = new File(secureRoot.getAbsolutePath() + '/' + filename);
      final boolean readable = file.setReadable(true);

      if (!readable) {
        throw new Exception("Can't read from file: " + filename);
      }

      super.importData(file);
    } else {
      super.importData(sourceFile);
    }
  }

  /**
   * @see com.follett.fsc.core.k12.tools.imports.TextImportJavaSource#importRecord(java.util.List, int)
   *      <p/>
   *      Entry Point for data record (row in data file)
   */
  protected void importRecord(final List<String> record, final int lineNumber) throws Exception {
    if (lineNumber > 2) {
      final String studentId = record.get(Index.PERSON_ID.ordinal());
      final String email = record.get(Index.EMAIL.ordinal());
      final Boolean isDupe = Boolean.valueOf(record.get(Index.IS_DUPLICATE.ordinal()));

      SisStudent student = (SisStudent) mStudents.get(studentId);

      if (isDupe) {
        logInvalidRecord(lineNumber, studentId + "/" + email + " is a likely duplicate. Skipping.");
        incrementSkipCount();
      } else {
        if (student == null) {
          // log message about null student
          try {
            student = getStudentFromDbByID(studentId);
            setEmailOnPerson(student.getPerson(), email);
          } catch (Exception e) {
            logInvalidRecord(lineNumber, "Student should be #" + studentId + ": Possible deleted duplicate. ");
            incrementSkipCount();
          }
        } else {
          setEmailOnPerson(student.getPerson(), email);

          incrementMatchCount();
          incrementUpdateCount();
        }
      }
    } else {
      logInvalidRecord(lineNumber, "Header Row. Skipping.");
      incrementSkipCount();
    }
  }

  protected void setEmailOnPerson(final SisPerson person, final String email) {
    if (person != null) {
      // primary email
      person.setEmail01(email);
      // interlochen email
      person.setEmail02(email);

      getBroker().saveBeanForced(person);
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

  /**
   * Get student map
   */
  private void populateStudentMap() {
    final int year = Calendar.getInstance().get(Calendar.YEAR);

    final Criteria criteria = new Criteria();
    criteria.addGreaterOrEqualThan(SisStudent.COL_YOG, year);
    criteria.addEqualTo(SisStudent.REL_SCHOOL + "." + SisSchool.COL_SCHOOL_ID, mSchoolId);
    final QueryByCriteria query = new QueryByCriteria(SisStudent.class, criteria);
    mStudents = getBroker().getMapByQuery(query, SisStudent.REL_PERSON + "." + SisPerson.COL_PERSON_ID, 1000);

  }

  private SisStudent getStudentFromDbByID(final String personId) {
    final Criteria criteria = new Criteria();
    criteria.addEqualTo(SisStudent.REL_PERSON + "." + SisPerson.COL_PERSON_ID, personId);
    final QueryByCriteria query = new QueryByCriteria(SisStudent.class, criteria);
    return (SisStudent) getBroker().getBeanByQuery(query);
  }
}