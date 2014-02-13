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
    <meta charset="UTF-8">
    <meta http-equiv="Content-type" content="text/html; charset=UTF-8">
    <link href="sass/style.css" rel="stylesheet" type="text/css" />
  </head>
  <body>
    <header>Noise Particles
      <div id="audio_container" style="display:none">
        <div id="audio_player"></div>
        <a id="close_audio" href="javascript:void(0);">Close Audio Player</a>
      </div></header>
    <div id="menu_container">
      <?php include 'menu.php'; ?>
    </div>
    <div id="main_content" style="display:none"></div>
    <script type="application/javascript" src="/js/jquery-2.1.0.js"></script>
    <script type="application/javascript">
      $(document).ready(function () {
        var main_content = $('#main_content');
        $('li ul').hide();
        $('#dice_link').click(function() {
          $.get('/dice/index.html',function (data) {
            main_content.html(data).show();
          });
        });
        $('#portfolio_link').click(function () {
          $.get('/portfolio/index.php', function (data) {
            main_content.html(data).show();
          });
        });
        $('#resume_link').click( function () {
          $.get('/resume/index.php', function (data) {
            main_content.html(data).show();
          });
        });
        $('.music_list_link').click(function() {
          $(this).siblings().toggle();
          $(this).toggleClass('folder').toggleClass('folderOpen');
        });
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