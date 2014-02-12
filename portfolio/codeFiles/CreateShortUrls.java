import com.follett.fsc.core.k12.beans.ReferenceCode;
import com.follett.fsc.core.k12.beans.StudentContact;
import com.follett.fsc.core.k12.tools.procedures.ProcedureJavaSource;
import com.x2dev.sis.model.beans.SisPerson;
import com.x2dev.sis.model.beans.SisReferenceTable;
import com.x2dev.sis.model.beans.SisSchool;
import com.x2dev.sis.model.beans.SisStudent;
import com.x2dev.utils.StringUtils;
import org.apache.ojb.broker.query.Criteria;
import org.apache.ojb.broker.query.QueryByCriteria;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.*;

import static com.follett.fsc.core.k12.business.ModelProperty.PATH_DELIMITER;

public class CreateShortUrls extends ProcedureJavaSource {

  private final String GOOGLE_CLOUD_KEY = "PUT_THE_API_KEY_HERE";
  private final String GOOGLE_SHORTENER_URL = "https://www.googleapis.com/urlshortener/v1/url?key=" + GOOGLE_CLOUD_KEY;
  private final String FORM_URL_PREFIX = "http://jotformpro.com/form/";
  private final int MAX_MAP_SIZE = 1000;
  private final int REF_CODE_SIZE = 5;

  private final String GrantOid = "PSN00000028AL6";


  private boolean ERROR = false;

  private Map<String, SisStudent> mStudents;

  private ArrayList<Map<String, ReferenceCode>> mapArrayList;

  @Override
  protected void initialize() {
    final String DEPARTURE_TABLE_NAME = "Travel Departure Timeframes";
    final String ARRIVAL_TABLE_NAME = "Travel Arrival Timeframes";


    populateStudentMap();

    mapArrayList = new ArrayList<Map<String, ReferenceCode>>(10);
    mapArrayList.add(getReferenceCodes(DEPARTURE_TABLE_NAME));
    mapArrayList.add(getReferenceCodes(ARRIVAL_TABLE_NAME));
  }

  @Override
  protected void execute() throws Exception {

    int counter = 0;

    if (!ERROR) {
      // loop through student map
      for (Map.Entry<String, SisStudent> studentEntry : mStudents.entrySet()) {
        // loop through list of forms
        for (Map<String, ReferenceCode> map : mapArrayList) {
          // loop through each reference code for the form
          for (Map.Entry<String, ReferenceCode> referenceCodeEntry : map.entrySet()) {
            SisStudent s = studentEntry.getValue();
            String shortUrlFieldAlias = referenceCodeEntry.getValue().getLocalCode();

            if (!StringUtils.isNullOrEmpty((String) s.getFieldValueByAlias(shortUrlFieldAlias))) {
              logMessage("Skipping " + shortUrlFieldAlias + " form for " + s.getNameView());
              continue;
            }

            JSONReader jr = new CreateShortUrls().new JSONReader();

            // loop through each line on reference table, pull field alias and value from table before creating form link

            // Form ID is stored on the state code in the appropriate row in the appropriate reference table
            String formId = referenceCodeEntry.getValue().getStateCode();

            if (StringUtils.isNullOrEmpty(formId)) {
              logMessage("Skipping form: " + formId + " / " + shortUrlFieldAlias);
              continue;
            }

            SisPerson p = s.getPerson();
            String contactEmail = s.getContact(0).getPerson().getEmail01();

            String formUrl = createFormUrl(formId, p.getFirstName(), p.getLastName(), contactEmail, p.getPersonId(), referenceCodeEntry.getKey());
            counter++;
            String shortUrlResponse = shortenUrl(formUrl);

            Object json = jr.read(shortUrlResponse);
            if (json instanceof HashMap) {
              String shortUrl = "";

              LinkedHashMap<String, Object> jsonMap = (LinkedHashMap<String, Object>) json;
              for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey())) {
                  shortUrl = entry.getValue().toString();
                }
              }

              // FieldB023
              logMessage("Shortened URL for " + s.getNameView() + " is " + shortUrl);

              s.setFieldValueByAlias(shortUrlFieldAlias, shortUrl);
              getBroker().saveBean(s);

            } else {
              logMessage("JSON was returned in an unexpected way.");
              logMessage((String) json);
            }

            if (counter > 50)
              break;

            Thread.sleep(2000);
          }
          if (counter > 50)
            break;
        }
        if (counter > 50)
          break;
      }
    }
  }

  private void populateStudentMap() {
    // find student contact record
    final Criteria criteria = new Criteria();
    criteria.addEqualTo(SisStudent.REL_SCHOOL + PATH_DELIMITER + SisSchool.COL_SCHOOL_ID, "IAA");
    final QueryByCriteria query = new QueryByCriteria(SisStudent.class, criteria);
    mStudents = getBroker().getMapByQuery(query, SisStudent.REL_PERSON + PATH_DELIMITER + SisPerson.COL_FIELD_B003, MAX_MAP_SIZE);


//    SisPerson grant = (SisPerson) getBroker().getBeanByOid(SisPerson.class, GrantOid);
//    logMessage("Found " + grant.getNameView() + " / " + Boolean.toString(grant.getStudentIndicator()));
//    mStudents = new HashMap<String, SisStudent>();
//    SisStudent gs = grant.getStudent();
//    String gId = grant.getFieldB003();
//    mStudents.put(gId, gs);

    if (mStudents.isEmpty()) {
      logMessage("Student map empty...");
      ERROR = true;
    }

    logMessage("Found " + mStudents.size() + " students.");
  }

  private Map<String, ReferenceCode> getReferenceCodes(String tableName) {

    final Criteria criteria = new Criteria();
    criteria.addEqualTo(ReferenceCode.REL_REFERENCE_TABLE + PATH_DELIMITER + SisReferenceTable.COL_USER_NAME, tableName);
    final QueryByCriteria query = new QueryByCriteria(ReferenceCode.class, criteria);
    return getBroker().getMapByQuery(query, ReferenceCode.COL_CODE, REF_CODE_SIZE);
  }

  private String createFormUrl(String formId, String nameFirst, String nameLast, String parentEmail, String studentId, String reason) {
    if (!StringUtils.isNullOrEmpty(formId)) {
      StringBuilder url = new StringBuilder(FORM_URL_PREFIX).append(formId);

      url.append("?studentFirst=").append(nameFirst);
      url.append("&studentLast=").append(nameLast);
      url.append("&parent=").append(parentEmail);
      url.append("&studentid=").append(studentId);
      url.append("&reasonFor3=").append(reason);

      return url.toString();
    } else {
      return null;
    }
  }

  private String shortenUrl(String longUrl) {
    String postData = "{ \"longUrl\" : \"" + longUrl + "\" }";

    logMessage("Shortening url: " + longUrl);

    String responseJson = "";

    try {
      URL url = new URL(GOOGLE_SHORTENER_URL);

      HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");

      conn.setDoOutput(true);
      DataOutputStream out = new DataOutputStream(conn.getOutputStream());
      out.writeBytes(postData);

      out.flush();
      out.close();

      int responseCode = conn.getResponseCode();
      logMessage("Response: " + responseCode);
      logMessage(conn.getResponseMessage());

      BufferedReader in = new BufferedReader(
          new InputStreamReader(conn.getInputStream())
      );
      StringBuilder sbResponse = new StringBuilder();

      String line;
      while ((line = in.readLine()) != null) {
        sbResponse.append(line);
      }

      responseJson = sbResponse.toString();

      logMessage(responseJson);

    } catch (MalformedURLException e) {
      logMessage("Bad URL: " + GOOGLE_SHORTENER_URL + "\n" + e.toString());

    } catch (IOException e) {
      logMessage("Couldn't make connection to " + GOOGLE_SHORTENER_URL + "\n" + e.toString());

    }

    return responseJson;
  }


  /**
   * ** METHODS USED TO PRINT DATA ****
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
    }
  }

  // Grabbed Stringtree JSON lib (because it was open source)
  //  from https://github.com/efficacy/stringtree/blob/master/src/main/java/org/stringtree/json/JSONReader.java
  public class JSONReader {

    protected final Object OBJECT_END = new Object();
    protected final Object ARRAY_END = new Object();
    protected final Object COLON = new Object();
    protected final Object COMMA = new Object();
    public final int FIRST = 0;
    public final int CURRENT = 1;
    public final int NEXT = 2;

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
//      logMessage("token: " + token); // enable this line to see the token stream
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
