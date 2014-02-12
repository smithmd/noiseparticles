import com.follett.fsc.core.framework.persistence.BeanQuery;
import com.follett.fsc.core.k12.beans.ReferenceCode;
import com.follett.fsc.core.k12.beans.X2BaseBean;
import com.follett.fsc.core.k12.business.MessageProperties;
import com.follett.fsc.core.k12.business.WriteEmailManager;
import com.follett.fsc.core.k12.tools.procedures.ProcedureJavaSource;
import com.x2dev.sis.model.beans.*;
import com.x2dev.utils.X2BaseException;
import com.x2dev.utils.types.PlainDate;
import org.apache.ojb.broker.query.Criteria;
import org.apache.ojb.broker.query.QueryByCriteria;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.*;
import java.util.*;

import static com.follett.fsc.core.k12.business.ModelProperty.PATH_DELIMITER;

/**
 * Created with IntelliJ IDEA.
 * User: smithmd
 * Date: 10/21/13
 * Time: 4:42 PM
 */

public class TravelFormReaderArrival extends ProcedureJavaSource {

  private final String JOTFORM_API_KEY = "JOTFORM_API_KEY_GOES_HERE";
  private final String ARRIVAL_FORM_ID = "33285140639960";
  private final String DEPARTURE_FORM_ID = "33353688494972";

  private final String PARAM_TYPE = "type";
  private final String ARRIVAL = "Arrival";

  private final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
  private final DateFormat TRAVEL_DATE_FORMAT = new SimpleDateFormat("MM-dd-yyyy");
  private final DateFormat SAVED_TRAVEL_DATE = new SimpleDateFormat("yyyy-MM-dd");

  private final String TRAVEL_EMAIL = "travelbox@interlochen.org";

  private String mType;

  /**
   * ****************************************************************************************************
   * ***** Integers that correspond to the correct question numbers on the DEPARTURE form response. *****
   * ****************************************************************************************************
   */
  private final Integer REASON_FOR_ARRIVAL = 3;
//  private final Integer TRAVELING_TO = 4;
//  private final Integer OTHER_DESTINATION_COMMENT = 5; // Please give name, address, phone number and relationship to student
  private final Integer STUDENT_FIRST_NAME = 6;
  private final Integer STUDENT_LAST_NAME = 7;
  private final Integer ARRIVAL_DATE = 8;
  private final Integer ARRIVING_BY = 9;
  private final Integer ARRIVAL_TIME = 10;
  private final Integer DRIVER_NAME = 11; // Please provide the name of the person who will be driving the student
//  private final Integer TICKET_LOCATION = 12;
  private final Integer AIRLINE = 13;
  private final Integer FLIGHT_NUMBER = 14;
  private final Integer TICKET_CONFIRMATION_NUMBER = 15;
  private final Integer UNACCOMPANIED_MINOR_SERVICE = 16;
//  private final Integer UNACCOMPANIED_MINOR_FEE_PAID = 17;
//  private final Integer PICK_UP_CONTACT_INFO = 18;
  private final Integer ADDITIONAL_COMMENTS = 19;
  private final Integer PARENT_EMAIL = 20;
  private final Integer UNIQUE_ID = 24;
  private final Integer APPROVAL_CODE = 26;
  private final Integer STUDENT_ID = 29;
  private final Integer READY_TO_SUBMIT = 35;
  private final Integer ARRIVING_WITH = 36;
  private final Integer RIDE_NEEDED = 37;

  /**
   * **********************************
   * *****     Approval Codes     *****
   * **********************************
   */
  private String APPROVAL_CODE_TABLE = "Travel Form Approvers";

  /**
   * **************************************
   * *****     Form Field Aliases     *****
   * **************************************
   */
  // arrival fields
  private final String FLD_ARRIVAL_SPRING_BREAK = "springBreakArrival";
  private final String FLD_ARRIVAL_SCHOOL = "initialArrival";
  private final String FLD_ARRIVAL_THANKSGIVING = "thanksgivingArrival";
  private final String FLD_ARRIVAL_WINTER = "winterBreakArrival";
  // departure fields
  private final String FLD_DEPARTURE_SCHOOL = "finalDeparture";
  private final String FLD_DEPARTURE_SPRING_BREAK = "springBreakDeparture";
  private final String FLD_DEPARTURE_THANKSGIVING = "thanksgivingDeparture";
  private final String FLD_DEPARTURE_WINTER = "winterBreakDeparture";

  // JSON Base results
  private Long mResponseCode;
  private String mMessage;
  private ArrayList<Object> mContent;
  private Long mLimitLeft;
  private LinkedHashMap<Object, Object> mResultSet;


  /**
   * ***********************************************
   * *****     Procedure Execution Methods     *****
   * ***********************************************
   */
  @Override
  protected void initialize() {
    mType = (String) getParameter(PARAM_TYPE);
  }

  @Override
  protected void execute() throws Exception {
    JSONReader jr = new TravelFormReaderArrival().new JSONReader();
    String formId = ARRIVAL_FORM_ID;

    String url = "https://api.jotform.com/form/" + formId + "/submissions?apiKey=" + JOTFORM_API_KEY;

    logMessage("Pulling form submissions from " + url);

    // read url response into a string
    Object json = jr.read(getSubmissionJson(url));
    if (json instanceof LinkedHashMap) {
      interrogateBaseJsonMap((LinkedHashMap<Object, Object>) json);

      if (mResponseCode == 200) {
        logMessage("Content: ");
//        arrayPrinter(mContent, "");

        ArrayList<Submission> submissions = new ArrayList<Submission>(20);

        for (Object o : mContent) {
          submissions.add(parseSubmission((LinkedHashMap<String, Object>) o));
        }

        for (Submission sub : submissions) {
          if (sub.getNew() == 0) {
            logMessage("Submission " + sub.getSubmissionId() + " not new or updated. Skipping.");
            continue;
          }

          logMessage(sub.toString());
          SisStudent student = getStudentById(sub.getStudentId());

          if (student != null) {
            boolean isApproved = submissionIsApproved(sub);
            boolean isReady = submissionIsReady(sub);

            if (isReady) {
              setFormStatus(student, ARRIVAL_FORM_ID.equals(sub.getFormId().toString()), getFormAlias(sub), getFormStatus(sub));
            }
            if (isApproved && isReady) {
              createOrUpdateEvent(student, sub);
            }
          } else {
            logMessage("Student is null. This shouldn't happen. Sending an email.");
            sendErrorEmail(sub);
          }
        }
      } else {
        logMessage("Response Code: " + mResponseCode.toString());
      }
    } else {
      logMessage("JSON was returned in an unexpected way.");
    }
  }

  /**
   * *********************************
   * *****     Email Methods     *****
   * *********************************
   */
  private void sendEmail(Submission submission, SisStudent student, String changes) {
    // what needs to be in email body?

    logMessage("Plans have changed, sending email...");
    logMessage("Changes:\n" + changes);

    WriteEmailManager wem = new WriteEmailManager(getOrganization());

    String studentName = student.getNameView();
    String body = createEmailBody(changes, submission);

    MessageProperties msg = new MessageProperties(TRAVEL_EMAIL, null, TRAVEL_EMAIL,
        "Travel Submission has changed for " + studentName, body);

    wem.connect();
    if (wem.sendMail(msg)) {
      logMessage("Success: Email Sent!");
    } else {
      logMessage("Failure: Email NOT Sent!");
    }
    wem.disconnect();
  }

  private void sendErrorEmail(Submission submission) {
    WriteEmailManager wem = new WriteEmailManager(getOrganization());

    String studentName = submission.getAnswers().get(STUDENT_FIRST_NAME) + " " + submission.getAnswers().get(STUDENT_LAST_NAME);
    String body = "There was a problem with " + studentName + ". There is a form with a null student. Hopefully I just printed a name.\n\n" + submission.toString();

    MessageProperties msg = new MessageProperties(getITEmails(), null, null, TRAVEL_EMAIL,
        "Travel Submission failure for " + studentName, body, null, null, null, null);

    wem.connect();
    if (wem.sendMail(msg)) {
      logMessage("Success: Error Email Sent!");
    } else {
      logMessage("Failure: Error Email NOT Sent!");
    }
    wem.disconnect();
  }

  private String generateLinkToForm(Submission submission) {
    return "http://submit.jotformpro.com/form.php?formID=" + submission.getFormId().toString() + "&sid=" + submission.getSubmissionId().toString() + "&mode=edit";
  }

  private String createEmailBody(String changes, Submission submission) {
    //print differences, and print link
    StringBuilder body = new StringBuilder();
    body.append(submission.getAnswers().get(REASON_FOR_ARRIVAL)).append(" travel plans have changed.\n\nThe following has changed:\n");
    body.append(changes);
    body.append("\n\nPlease re-approve the form here: ").append(generateLinkToForm(submission));
    return body.toString();
  }

  /**
   * **********************************
   * *****     Getter Methods     *****
   * **********************************
   */
  private SisStudent getStudentById(String id) {
    Criteria c = new Criteria();
    c.addEqualTo(SisStudent.REL_PERSON + PATH_DELIMITER + SisPerson.COL_PERSON_ID, id);
    BeanQuery q = createQueryByCriteria(SisStudent.class, c);
    return (SisStudent) getBroker().getBeanByQuery(q);
  }

  private String getFormStatus(Submission submission) {
//    final String received = "Received";
    final String incomplete = "Incomplete";
    final String complete = "Complete";

    boolean isApproved = submissionIsApproved(submission);
    boolean isReady = submissionIsReady(submission);

    String status = "";

    if (isApproved && isReady) {
      status = complete;
    } else if (isReady) {
      status = incomplete;
    }

    logMessage("Form status: " + status);

    return status;
  }

  private Map<String, ReferenceCode> getReferenceCodes(String tableName) {
    final Criteria criteria = new Criteria();
    criteria.addEqualTo(ReferenceCode.REL_REFERENCE_TABLE + PATH_DELIMITER + SisReferenceTable.COL_USER_NAME, tableName);
    final QueryByCriteria query = new QueryByCriteria(ReferenceCode.class, criteria);
    return getBroker().getMapByQuery(query, ReferenceCode.COL_CODE, 5);
  }

  private ArrayList<String> getITEmails() {
    ArrayList<String> emails = new ArrayList<String>(2);
    emails.add("smithmd@interlochen.org");

    return emails;
  }

  /**
   * getFormAlias
   *
   * @param submission
   * @return String - Alias of the field where we save the status on the student record
   */
  private String getFormAlias(Submission submission) {
    String reason = submission.getAnswers().get(REASON_FOR_ARRIVAL).toLowerCase();
    String alias = "";

    if (DEPARTURE_FORM_ID.equals(submission.getFormId().toString())) {
      if (reason.contains("final")) {
        alias = FLD_DEPARTURE_SCHOOL;
      } else if (reason.contains("thanksgiving")) {
        alias = FLD_DEPARTURE_THANKSGIVING;
      } else if (reason.contains("spring")) {
        alias = FLD_DEPARTURE_SPRING_BREAK;
      } else if (reason.contains("winter")) {
        alias = FLD_DEPARTURE_WINTER;
      }
    } else { // must be arrival form
      if (reason.contains("final")) {
        alias = FLD_ARRIVAL_SCHOOL;
      } else if (reason.contains("thanksgiving")) {
        alias = FLD_ARRIVAL_THANKSGIVING;
      } else if (reason.contains("spring")) {
        alias = FLD_ARRIVAL_SPRING_BREAK;
      } else if (reason.contains("winter")) {
        alias = FLD_ARRIVAL_WINTER;
      }
    }

    logMessage("Should save to " + alias);

    return alias;
  }

  private String getTravelMethod(String method) {
    method = method.toLowerCase();
    if (method.contains("airport")) {
      method = "TVC";
    } else if (method.contains("bus")) {
      method = "Bus";
    } else if (method.contains("car")) {
      method = "Car";
    }

    return method;
  }

  private String getSubmissionJson(String url) {
    StringBuilder sbJson = new StringBuilder();

    try {
      URL jotFormApi = new URL(url);
      URLConnection connection = jotFormApi.openConnection();
      BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));

      String line;

      while ((line = in.readLine()) != null) {
        sbJson.append(line);
      }
    } catch (MalformedURLException e) {
      logMessage("Bad URL: " + url + "\n" + e.toString());
    } catch (IOException e) {
      logMessage("Couldn't make connection to " + url + "\n" + e.toString());
    }

    return sbJson.toString();
  }

  private StudentEventTracking getEventBySubmissionId(String submissionId) {
    Criteria c = new Criteria();
    c.addEqualTo(StudentEventTracking.COL_FIELD_C008, submissionId);
    QueryByCriteria q = new QueryByCriteria(StudentEventTracking.class, c);
    return (StudentEventTracking) getBroker().getBeanByQuery(q);
  }

  private Date getSubmissionTravelTimestamp(Submission submission) {
    String date = submission.getAnswers().get(ARRIVAL_DATE);
    String time = submission.getAnswers().get(ARRIVAL_TIME);

    if (time.length() == 7) {
      time = "0" + time;
    }

    String datetime = date + " " + time;
    //11-28-2013 6:08 AM

    DateFormat df = new SimpleDateFormat("M-d-yyyy HH:mm aa");
    df.setLenient(true);

    Date d;
    try {
      d = df.parse(datetime);
    } catch (ParseException e) {
      d = new Date();
    }

    return d;
  }

  private String getSubmissionMilitaryTime(Submission submission) {
    String time = submission.getAnswers().get(ARRIVAL_TIME);
    SimpleDateFormat parser = new SimpleDateFormat("hh:mm aa");
    SimpleDateFormat printer = new SimpleDateFormat("HH:mm");

    try {
      Date d = parser.parse(time);
      time = printer.format(d);
    } catch (ParseException pe) {
      logMessage("Couldn't parse travel time");
    }

    return time;
  }

  /**
   * **********************************
   * *****     Setter Methods     *****
   * **********************************
   */
  private void setFormStatus(SisStudent student, boolean isArrival, String fieldAlias, String status) {
    logMessage(student.getNameView() + ": Saving " + status + " to " + fieldAlias);
    student.setFieldValueByAlias(fieldAlias, status);
    getBroker().saveBean(student);
  }

  private void createOrUpdateEvent(SisStudent student, Submission submission) {
    StudentEventTracking event = getEventBySubmissionId(submission.getSubmissionId().toString());

    // if can't find event, create a new one
    if (event == null) {
      event = (StudentEventTracking) X2BaseBean.newInstance(StudentEventTracking.class, getUser().getPersistenceKey());
      event.setStudentOid(student.getOid());
      event.setFieldValueByAlias("JFSubID", submission.getSubmissionId().toString());
      event.setDistrictContextOid(getOrganization().getCurrentContextOid());
      logMessage("Creating new Student Event.");
    } else {
      // if anything in the event changes, send an email to travel
      StringBuilder sbChanges = new StringBuilder();
      if (submissionHasChanged(submission, event, sbChanges)) {

        sendEmail(submission, student, sbChanges.toString());
        // don't remove travel approval until we have a good place to keep a temporary event
        // postRemoveSubmissionApproval(submission);
      }
    }

    // timestamp should be the travel date & time
    Date timestamp = getSubmissionTravelTimestamp(submission);

    // Departure Form
    if (ARRIVAL_FORM_ID.equals(submission.getFormId().toString())) {
      event.setFieldValueByAlias("airline", submission.getAnswers().get(AIRLINE));
      event.setFieldValueByAlias("is-um", submission.getAnswers().get(UNACCOMPANIED_MINOR_SERVICE));
      event.setFieldValueByAlias("driver", submission.getAnswers().get(DRIVER_NAME));
      event.setFieldValueByAlias("flight", submission.getAnswers().get(FLIGHT_NUMBER));
      event.setFieldValueByAlias("reason-for-travel", submission.getAnswers().get(REASON_FOR_ARRIVAL));
      event.setFieldValueByAlias("submission-timestamp", DATE_FORMAT.format(timestamp));
      event.setFieldValueByAlias("ticket-confirmation-num", submission.getAnswers().get(TICKET_CONFIRMATION_NUMBER));
      try {
        Date departureDate = TRAVEL_DATE_FORMAT.parse(submission.getAnswers().get(ARRIVAL_DATE).trim());
        event.setFieldValueByAlias("travel-date", SAVED_TRAVEL_DATE.format(departureDate));
      } catch (ParseException pe) {
        logMessage("couldn't parse date: "+ submission.getAnswers().get(ARRIVAL_DATE));
      }
      event.setFieldValueByAlias("travel-time", getSubmissionMilitaryTime(submission));
      String method = getTravelMethod(submission.getAnswers().get(ARRIVING_BY));
      event.setFieldValueByAlias("travel-method", method);
      event.setComment(submission.getAnswers().get(ADDITIONAL_COMMENTS));
      event.setFieldValueByAlias("arrive-who", submission.getAnswers().get(ARRIVING_WITH));
      event.setFieldValueByAlias("need-ride", submission.getAnswers().get(RIDE_NEEDED));
    }

    PlainDate eventDate;
    if (submission.getUpdatedAt() != null) {
      eventDate = new PlainDate(submission.getUpdatedAt());
    } else {
      eventDate = new PlainDate(submission.getCreatedAt());
    }
    event.setEventDate(eventDate);
    event.setEventType("Travel - Arrival");

    // save the student event tracking object
    getBroker().saveBeanForced(event);
  }

  /**
   * ****************************************
   * *****     JSON Parsing Methods     *****
   * ****************************************
   */
  private void interrogateBaseJsonMap(LinkedHashMap<Object, Object> map) {
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
//      System.out.println(entry.getKey().toString() + " : " + entry.getValue().getClass());
      String key = entry.getKey().toString();
      if ("responseCode".equalsIgnoreCase(key)) {
        mResponseCode = (Long) entry.getValue();
      } else if ("resultSet".equalsIgnoreCase(key)) {
        mResultSet = (LinkedHashMap<Object, Object>) entry.getValue();
      } else if ("message".equalsIgnoreCase(key)) {
        mMessage = entry.getValue().toString();
      } else if ("content".equalsIgnoreCase(key)) {
        mContent = (ArrayList<Object>) entry.getValue();
      } else if ("limit-left".equalsIgnoreCase(key)) {
        mLimitLeft = (Long) entry.getValue();
      }
    }
  }

  private Submission parseSubmission(LinkedHashMap<String, Object> submission) {
    return new Submission(submission);
  }

  /**
   * ********************************************
   * *****     JSON/API Posting Methods     *****
   * ********************************************
   */
  private void postRemoveSubmissionApproval(Submission submission) {
    String urlParameters = "submission[" + APPROVAL_CODE + "]=";
    try {
      URL url = new URL("https://api.jotform.com/submission/" + submission.getSubmissionId().toString() + "?apiKey=" + JOTFORM_API_KEY);
      URLConnection conn = url.openConnection();

      conn.setDoOutput(true);

      OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());

      writer.write(urlParameters);
      writer.flush();

      String line;
      BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));

      while ((line = reader.readLine()) != null) {
        System.out.println(line);
      }

      writer.close();
      reader.close();
    } catch (MalformedURLException e) {
      logMessage("MalformedURLException: Bad url for post\n" + e.toString());
    } catch (IOException e) {
      logMessage("IOException: Can't POST\n" + e.toString());
    }
  }

  /**
   * ************************************
   * *****     Boolean Methods     ******
   * ************************************
   */
  private boolean submissionIsApproved(Submission submission) {
    boolean approved = false;

    String approvalCode = submission.getAnswers().get(APPROVAL_CODE);

    Set<String> codes = getReferenceCodes(APPROVAL_CODE_TABLE).keySet();

    for (String code : codes) {
      if (approvalCode.trim().equalsIgnoreCase(code)) {
        approved = true;
      }
    }

    return approved;
  }

  private boolean submissionIsReady(Submission submission) {
    boolean isReady = false;

    String readyToSubmit = submission.getAnswers().get(READY_TO_SUBMIT);

    if (readyToSubmit.toLowerCase().contains("yes,")) {
      isReady = true;
      logMessage("Submission is ready to submit.");
    }

    return isReady;
  }

  private boolean submissionHasChanged(Submission submission, StudentEventTracking event, StringBuilder sbChanges) {
    boolean isDifferent = false;

    // set up strings for IF statements
    String timestamp = DATE_FORMAT.format(getSubmissionTravelTimestamp(submission));

    String method = getTravelMethod(submission.getAnswers().get(ARRIVING_BY));
    Date departureDate = new Date();
    try {
      departureDate = TRAVEL_DATE_FORMAT.parse(submission.getAnswers().get(ARRIVAL_DATE));
    } catch (ParseException pe) {
      departureDate = new Date();
    }
    if (event.getFieldValueByAlias("travel-date") != null) {
      logMessage(event.getFieldValueByAlias("travel-date").getClass().toString());
    }

    if (!submission.getAnswers().get(AIRLINE).equals(event.getFieldValueByAlias("airline"))) {
      sbChanges.append(submission.getQuestions().get(AIRLINE));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("airline"));
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(AIRLINE)).append("\n");
      isDifferent = true;
    }
    if (!submission.getAnswers().get(UNACCOMPANIED_MINOR_SERVICE).equals(event.getFieldValueByAlias("is-um"))) {
      sbChanges.append(submission.getQuestions().get(UNACCOMPANIED_MINOR_SERVICE));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("is-um"));
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(UNACCOMPANIED_MINOR_SERVICE)).append("\n");
      isDifferent = true;
    }
    if (!submission.getAnswers().get(DRIVER_NAME).equals(event.getFieldValueByAlias("driver"))) {
      sbChanges.append(submission.getQuestions().get(DRIVER_NAME));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("driver"));
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(DRIVER_NAME)).append("\n");
      isDifferent = true;
    }
    if (!submission.getAnswers().get(FLIGHT_NUMBER).equals(event.getFieldValueByAlias("flight"))) {
      sbChanges.append(submission.getQuestions().get(FLIGHT_NUMBER));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("flight"));
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(FLIGHT_NUMBER)).append("\n");
      isDifferent = true;
    }
    if (!submission.getAnswers().get(REASON_FOR_ARRIVAL).equals(event.getFieldValueByAlias("reason-for-travel"))) {
      sbChanges.append(submission.getQuestions().get(REASON_FOR_ARRIVAL));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("reason-for-travel"));
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(REASON_FOR_ARRIVAL)).append("\n");
      isDifferent = true;
    }
    if (!submission.getAnswers().get(TICKET_CONFIRMATION_NUMBER).equals(event.getFieldValueByAlias("ticket-confirmation-num"))) {
      sbChanges.append(submission.getQuestions().get(TICKET_CONFIRMATION_NUMBER));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("ticket-confirmation-num"));
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(TICKET_CONFIRMATION_NUMBER)).append("\n");
      isDifferent = true;
    }
    if (!method.equals(event.getFieldValueByAlias("travel-method"))) {
      sbChanges.append(submission.getQuestions().get(ARRIVING_BY));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("travel-method"));
      sbChanges.append("\t Changed To: ").append(method).append("\n");
      isDifferent = true;
    }
    if (event.getFieldValueByAlias("travel-date") != null &&
        !event.getFieldValueByAlias("travel-date").equals(SAVED_TRAVEL_DATE.format(departureDate)))
    {
      sbChanges.append(submission.getQuestions().get(ARRIVAL_DATE));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("travel-date"));
      sbChanges.append("\t Changed To: ").append(SAVED_TRAVEL_DATE.format(departureDate)).append("\n");
      isDifferent = true;
    }
    if (!getSubmissionMilitaryTime(submission).equals(event.getFieldValueByAlias("travel-time"))) {
      sbChanges.append(submission.getQuestions().get(ARRIVAL_TIME));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("travel-time"));
      sbChanges.append("\t Changed To: ").append(getSubmissionMilitaryTime(submission)).append("\n");
      isDifferent = true;
    }
    if(event.getComment() != null && !event.getComment().equals(submission.getAnswers().get(ADDITIONAL_COMMENTS))) {
      sbChanges.append("\n").append(submission.getQuestions().get(ADDITIONAL_COMMENTS));
      sbChanges.append("\n\tWas: ").append(event.getComment());
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(ADDITIONAL_COMMENTS)).append("\n");
      isDifferent = true;
    }
    if(event.getFieldValueByAlias("arrive-who") != null && !event.getFieldValueByAlias("arrive-who").equals(submission.getAnswers().get(ARRIVING_WITH))) {
      sbChanges.append("\n").append(submission.getQuestions().get(ARRIVING_WITH));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("arrive-who"));
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(ARRIVING_WITH));
      isDifferent = true;
    }
    if(event.getFieldValueByAlias("need-ride") != null && !event.getFieldValueByAlias("need-ride").equals(submission.getAnswers().get(RIDE_NEEDED))) {
      sbChanges.append("\n").append(submission.getQuestions().get(RIDE_NEEDED));
      sbChanges.append("\n\tWas: ").append(event.getFieldValueByAlias("need-ride"));
      sbChanges.append("\t Changed To: ").append(submission.getAnswers().get(RIDE_NEEDED));
      isDifferent = true;
    }

    return isDifferent;
  }

  /**
   * *******************************************
   * *****     Pretty Printing Methods     *****
   * *******************************************
   */

  private void mapPrinter(LinkedHashMap<Object, Object> map, String indent) {
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      if (entry != null && entry.getKey() != null) {
        if (entry.getKey() instanceof String) {
          if (entry.getValue() == null) {
            entry.setValue("null");
          }
          if (entry.getValue() instanceof LinkedHashMap) {
            logMessage(indent + entry.getKey().toString() + " : {");
            mapPrinter((LinkedHashMap<Object, Object>) entry.getValue(), indent + "\t");
            logMessage(indent + "}");
          } else if (entry.getValue() instanceof ArrayList) {
            logMessage(indent + entry.getKey().toString() + " : [");
            arrayPrinter((ArrayList<Object>) entry.getValue(), indent + "\t");
            logMessage(indent + "]");
          } else {
            logMessage(indent + entry.getKey().toString() + " : " + entry.getValue().toString());
          }
        } else {
          logMessage(indent + "Key is of type " + entry.getKey().getClass());
        }
      }
    }
  }

  private void arrayPrinter(ArrayList<Object> arrayList, String indent) {

    for (Object o : arrayList) {
//      logMessage(o.getClass());
      if (o instanceof LinkedHashMap) {
        mapPrinter((LinkedHashMap<Object, Object>) o, indent);
      } else if (o instanceof String) {
        logMessage(indent + o.toString());
      }
      logMessage(indent + ",");
    }
  }

  /**
   * ****************************************************************************
   * *****************                  CLASSES                ******************
   * ****************************************************************************
   */

  /**
   * Submission class
   * ** Class holds data about each submission that is parsed from JSON string
   */
  public class Submission {
    private Long mSubmissionId;
    private Long mFormId;
    private String mIpAddress;
    private Date mCreatedAt;
    private String mStatus;
    private Integer mNew;
    private Integer mFlag;
    private Date mUpdatedAt;
    private TreeMap<Integer, String> mAnswers;
    private TreeMap<Integer, String> mQuestions;

    public Submission(LinkedHashMap<String, Object> s) {
      mSubmissionId = Long.parseLong((String) s.get("id"));
      mFormId = Long.parseLong((String) s.get("form_id"));
      mIpAddress = (String) s.get("ip");
      mCreatedAt = parseDate((String) s.get("created_at"));
      mStatus = (String) s.get("status");
      mNew = Integer.parseInt((String) s.get("new"));
      mFlag = Integer.parseInt((String) s.get("flag"));
      mUpdatedAt = parseDate((String) s.get("updated_at"));

      if (s.get("answers") instanceof LinkedHashMap) {
        buildAnswerMap((LinkedHashMap<String, Object>) s.get("answers"));
        buildQuestionMap((LinkedHashMap<String, Object>) s.get("answers"));
      }
    }

    public Map<Integer, String> getAnswers() {
      return mAnswers;
    }

    public Map<Integer, String> getQuestions() {
      return mQuestions;
    }

    public Long getSubmissionId() {
      return mSubmissionId;
    }

    public Long getFormId() {
      return mFormId;
    }

    public Date getCreatedAt() {
      return mCreatedAt;
    }

    public Date getUpdatedAt() {
      return mUpdatedAt;
    }

    public String getStudentId() {
      return mAnswers.get(STUDENT_ID);
    }

    public Integer getNew() {
      return mNew;
    }

    public String toString() {
      StringBuilder sb = new StringBuilder();

      sb.append("\nid : ").append(mSubmissionId);
      sb.append("\nform_id : ").append(mFormId);
      sb.append("\nip : ").append(mIpAddress);
      sb.append("\ncreated_at : ").append(mCreatedAt.toString());
      sb.append("\nstatus : ").append(mStatus);
      sb.append("\nnew : ").append(mNew);
      sb.append("\nflag : ").append(mFlag);
      if (mUpdatedAt != null) {
        sb.append("\nupdated_at : ").append(mUpdatedAt.toString());
      }

      sb.append("\nanswers : ");
      for (Map.Entry<Integer, String> entry : mAnswers.entrySet()) {
        sb.append("\n\t").append(entry.getKey()).append(" : ").append(entry.getValue());
      }

      return sb.toString();
    }

    private Date parseDate(String date) {
      Date d;
      //2013-11-18 14:10:56
      try {
        d = DATE_FORMAT.parse(date);
      } catch (Exception e) {
        d = null;
      }
      return d;
    }

    private void buildAnswerMap(LinkedHashMap<String, Object> answers) {
      if (mAnswers == null) {
        mAnswers = new TreeMap<Integer, String>();
      }

      for (Map.Entry<String, Object> a : answers.entrySet()) {

        mAnswers.put(Integer.parseInt(a.getKey()), parseAnswer((LinkedHashMap<String, Object>) a.getValue()));
      }
    }

    private void buildQuestionMap(LinkedHashMap<String, Object> answers) {
      if (mQuestions == null) {
        mQuestions = new TreeMap<Integer, String>();
      }

      for (Map.Entry<String, Object> a : answers.entrySet()) {

        mQuestions.put(Integer.parseInt(a.getKey()), parseQuestion((LinkedHashMap<String, Object>) a.getValue()));
      }

    }

    private String parseAnswer(LinkedHashMap<String, Object> answer) {

      Object a = answer.get("answer");

      String result = "";
      if (a != null) {
        if (a instanceof String) {
          result = (String) a;
        } else if (a instanceof HashMap) {
          result = (String) answer.get("prettyFormat");
        }
      }

      return result;
    }

    private String parseQuestion(LinkedHashMap<String, Object> answer) {

      Object a = answer.get("text");

      String result = "";
      if (a != null && a instanceof String) {
        result = (String) a;
      }

      return result;
    }
  }


  /**
   * JSONReader class
   * ** class used to parse data from json result from JotForm.
   */
  // Grabbed Stringtree JSON lib (because it was open source)
  //  from https://github.com/efficacy/stringtree/blob/master/src/main/java/org/stringtree/json/JSONReader.java
  public class JSONReader {

    protected final Object OBJECT_END = new Object();
    protected final Object ARRAY_END = new Object();
    protected final Object COLON = new Object();
    protected final Object COMMA = new Object();
    public static final int FIRST = 0;
    public static final int CURRENT = 1;
    public static final int NEXT = 2;

    protected Map<Character, Character> escapes = new HashMap<Character, Character>();

    {
      escapes.put(Character.valueOf('"'), Character.valueOf('"'));
      escapes.put(Character.valueOf('\\'), Character.valueOf('\\'));
      escapes.put(Character.valueOf('/'), Character.valueOf('/'));
      escapes.put(Character.valueOf('b'), Character.valueOf('\b'));
      escapes.put(Character.valueOf('f'), Character.valueOf('\f'));
      escapes.put(Character.valueOf('n'), Character.valueOf('\n'));
      escapes.put(Character.valueOf('r'), Character.valueOf('\r'));
      escapes.put(Character.valueOf('t'), Character.valueOf('\t'));
    }

    protected CharacterIterator it;
    protected char c;
    protected Object token;
    protected StringBuffer buf = new StringBuffer();

    public void reset() {
      it = null;
      c = 0;
      token = null;
      buf.setLength(0);
    }

    protected char next() {
      c = it.next();
      return c;
    }

    protected void skipWhiteSpace() {
      while (Character.isWhitespace(c)) {
        next();
      }
    }

    public Object read(CharacterIterator ci, int start) {
      reset();
      it = ci;
      switch (start) {
        case FIRST:
          c = it.first();
          break;
        case CURRENT:
          c = it.current();
          break;
        case NEXT:
          c = it.next();
          break;
      }
      return read();
    }

    public Object read(CharacterIterator it) {
      return read(it, NEXT);
    }

    public Object read(String string) {
      return read(new StringCharacterIterator(string), FIRST);
    }

    protected Object read() {
      skipWhiteSpace();
      char ch = c;
      next();
      switch (ch) {
        case '"':
          token = string();
          break;
        case '[':
          token = array();
          break;
        case ']':
          token = ARRAY_END;
          break;
        case ',':
          token = COMMA;
          break;
        case '{':
          token = object();
          break;
        case '}':
          token = OBJECT_END;
          break;
        case ':':
          token = COLON;
          break;
        case 't':
          next();
          next();
          next(); // assumed r-u-e
          token = Boolean.TRUE;
          break;
        case 'f':
          next();
          next();
          next();
          next(); // assumed a-l-s-e
          token = Boolean.FALSE;
          break;
        case 'n':
          next();
          next();
          next(); // assumed u-l-l
          token = null;
          break;
        default:
          c = it.previous();
          if (Character.isDigit(c) || c == '-') {
            token = number();
          }
      }
      // System.out.println("token: " + token); // enable this line to see the token stream
      return token;
    }

    protected Object object() {
      Map<Object, Object> ret = new LinkedHashMap<Object, Object>();
      Object key = read();
      while (token != OBJECT_END) {
        read(); // should be a colon
        if (token != OBJECT_END) {
          ret.put(key, read());
          if (read() == COMMA) {
            key = read();
          }
        }
      }

      return ret;
    }

    protected Object array() {
      List<Object> ret = new ArrayList<Object>();
      Object value = read();
      while (token != ARRAY_END) {
        ret.add(value);
        if (read() == COMMA) {
          value = read();
        }
      }
      return ret;
    }

    protected Object number() {
      int length = 0;
      boolean isFloatingPoint = false;
      buf.setLength(0);

      if (c == '-') {
        add();
      }
      length += addDigits();
      if (c == '.') {
        add();
        length += addDigits();
        isFloatingPoint = true;
      }
      if (c == 'e' || c == 'E') {
        add();
        if (c == '+' || c == '-') {
          add();
        }
        addDigits();
        isFloatingPoint = true;
      }

      String s = buf.toString();
      return isFloatingPoint
          ? (length < 17) ? (Object) Double.valueOf(s) : new BigDecimal(s)
          : (length < 19) ? (Object) Long.valueOf(s) : new BigInteger(s);
    }

    protected int addDigits() {
      int ret;
      for (ret = 0; Character.isDigit(c); ++ret) {
        add();
      }
      return ret;
    }

    protected Object string() {
      buf.setLength(0);
      while (c != '"') {
        if (c == '\\') {
          next();
          if (c == 'u') {
            add(unicode());
          } else {
            Object value = escapes.get(Character.valueOf(c));
            if (value != null) {
              add(((Character) value).charValue());
            }
          }
        } else {
          add();
        }
      }
      next();

      return buf.toString();
    }

    protected void add(char cc) {
      buf.append(cc);
      next();
    }

    protected void add() {
      add(c);
    }

    protected char unicode() {
      int value = 0;
      for (int i = 0; i < 4; ++i) {
        switch (next()) {
          case '0':
          case '1':
          case '2':
          case '3':
          case '4':
          case '5':
          case '6':
          case '7':
          case '8':
          case '9':
            value = (value << 4) + c - '0';
            break;
          case 'a':
          case 'b':
          case 'c':
          case 'd':
          case 'e':
          case 'f':
            value = (value << 4) + (c - 'a') + 10;
            break;
          case 'A':
          case 'B':
          case 'C':
          case 'D':
          case 'E':
          case 'F':
            value = (value << 4) + (c - 'A') + 10;
            break;
        }
      }
      return (char) value;
    }
  }
}
