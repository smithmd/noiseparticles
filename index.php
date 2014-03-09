<?php
  /**
   * Created by PhpStorm.
   * User: msmith
   * Date: 2/8/14
   * Time: 9:11 PM
   */
?>

<html>
  <head>
    <title>NoiseParticles</title>
    <meta charset="UTF-8">
    <meta http-equiv="Content-type" content="text/html; charset=UTF-8">
    <link href="sass/style.css" rel="stylesheet" type="text/css" />
  </head>
  <body>
    <header>
      <h2>Noise Particles</h2>
      <div id="audio_container" style="display:none">
        <div id="audio_player"></div>
        <h6><a id="close_audio" href="javascript:void(0);">Close Audio Player</a></h6>
      </div>
    </header>
    <div id="menu_container">
      <?php include 'menu.php'; ?>
    </div>
    <div id="main_content" style="display:none"></div>
    <script type="application/javascript" src="/js/jquery-2.1.0.js"></script>
    <script type="application/javascript">
      $(document).ready(function () {
        var main_content = $('#main_content');
        $('li ul').hide();
        $('#dice').click(function() {
          $.get('/dice/index.html',function (data) {
            main_content.html(data).show();
          });
        });
        $('#portfolio').click(function () {
          $.get('/portfolio/index.php', function (data) {
            main_content.html(data).show();
          });
        });
        $('#resume').click( function () {
          $.get('/resume/index.php', function (data) {
            main_content.html(data).show();
          });
        });
        $('.music_list_link').click(function() {
          $(this).siblings().toggle();
          $(this).toggleClass('folder').toggleClass('folderOpen');
        });

        $(window.location.hash).trigger('click');
      });

      function loadAudio(filename) {
        $('#audio_container').show();
        $('#close_audio').click(function () {
          $('#audio_container').hide();
        });
        $.get('/audio.php?file='+filename, function (data) {
          $("#audio_player").html(data);
        });
      }
    </script>
  </body>
</html>