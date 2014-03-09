<link rel="stylesheet" type="text/css" href="/sass/prism.css"/>
<span class="filename">AcademyStudentImport.java:</span>
<span class="file_links">
  <a href="javascript:void(0);" onClick="loadPrism('AcademyStudentImport.java')">View</a> |
  <a href="/portfolio/codeFiles/AcademyStudentImport.java">Download</a>
</span>

<span class="filename">CreateShortUrls.java:</span>
<span class="file_links">
  <a href="javascript:void(0);" onclick="loadPrism('CreateShortUrls.java'); document.getElementById('codeTitle').style.borderTop ='2px solid #111'; ">View</a> |
  <a href="/portfolio/codeFiles/CreateShortUrls.java">Download</a>
</span>

<span class="filename">GmailAddressImport.java:</span>
<span class="file_links">
  <a href="javascript:void(0);" onclick="loadPrism('GmailAddressImport.java'); document.getElementById('codeTitle').style.borderTop ='2px solid #111'; ">View</a> |
  <a href="/portfolio/codeFiles/GmailAddressImport.java">Download</a>
</span>

<span class="filename">ParentImport.java:</span>
<span class="file_links">
  <a href="javascript:void(0);" onclick="loadPrism('ParentImport.java'); document.getElementById('codeTitle').style.borderTop ='2px solid #111'; ">View</a> |
  <a href="/portfolio/codeFiles/ParentImport.java">Download</a>
</span>

<span class="filename">TravelFormReaderArrival.java:</span>
<span class="file_links">
  <a href="javascript:void(0);" onclick="loadPrism('TravelFormReaderArrival.java'); document.getElementById('codeTitle').style.borderTop ='2px solid #111'; ">View</a> |
  <a href="/portfolio/codeFiles/TravelFormReaderArrival.java">Download</a>
</span>

<span id="codeTitle"></span><br />
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