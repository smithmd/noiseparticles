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
  </head>
  <body>
    <?php include 'menu.php'; ?>
    <div id="main_content"></div>
    <script type="application/javascript" src="/js/jquery-2.1.0.js"></script>
    <script type="application/javascript">
      $(document).ready(function () {
        var main_content = $('#main_content');
        $('li ul').hide();
        $('#dice_link').click(function() {
          $.get('/dice/index.html',function (data) {
            main_content.html(data);
          });
        });
        $('#portfolio_link').click(function () {
          $.get('/portfolio', function (data) {
            main_content.html(data);
          });
        });
        $('#resume_link').click( function () {
          $.get('/resume', function (data) {
            main_content.html(data);
          });
        });
        $('.music_list_link').click(function() {
          $(this).siblings().toggle();
        });
      });
    </script>
  </body>
</html>