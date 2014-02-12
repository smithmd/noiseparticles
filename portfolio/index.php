<link rel="stylesheet" type="text/css" href="/sass/prism.css"/>
<span>AcademyStudentImport.java:
  <a href="javascript:void(0);" onClick="loadPrism('AcademyStudentImport.java')">View</a> |
  <a href="/portfolio/codeFiles/AcademyStudentImport.java">Download</a></span><br/>

<span>CreateShortUrls.java:
  <a href="javascript:void(0);" onclick="loadPrism('CreateShortUrls.java')">View</a> |
  <a href="/portfolio/codeFiles/CreateShortUrls.java">Download</a></span><br />

<span>GmailAddressImport.java:
  <a href="javascript:void(0);" onclick="loadPrism('GmailAddressImport.java')">View</a> |
  <a href="/portfolio/codeFiles/GmailAddressImport.java">Download</a></span><br />

<span>ParentImport.java:
  <a href="javascript:void(0);" onclick="loadPrism('ParentImport.java')">View</a> |
  <a href="/portfolio/codeFiles/ParentImport.java">Download</a></span><br />

<span>TravelFormReaderArrival.java:
  <a href="javascript:void(0);" onclick="loadPrism('TravelFormReaderArrival.java')">View</a> |
  <a href="/portfolio/codeFiles/TravelFormReaderArrival.java">Download</a></span><br />
<br />
<span id="codeTitle" style="text-align: center;"></span><br />
<pre class="line-numbers"><code id="prism-placeholder" class="language-java"></code></pre>
<script type="application/javascript" src="/js/prism.js"></script>
<script type="application/javascript" src="/js/jquery-2.1.0.js"></script>
<script type="application/javascript">
  function loadPrism(filename) {
    var prefix = '/portfolio/codeFiles/';
    $.get(prefix + filename, function(data) {
      $("#prism-placeholder").html(data);
      $("#codeTitle").html(filename);
      Prism.highlightAll();
    });
  }
</script>