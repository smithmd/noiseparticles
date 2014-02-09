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
    <script type="application/javascript" src="/js/jquery-2.1.0.min.js"></script>
  </head>
  <body>
    <?php include 'menu.php'; ?>
    <div id="main_content"></div>
    <script type="application/javascript">
      $(document).ready(function () {
        var main_content = $('#main_content');
        var sub_lists = $('li ul');
        sub_lists.hide();
        $('#dice_link').click(function() {
          $.get('/dice/index.html',function (data) {
            main_content.html(data);
          });
        });
        $('#portfolio_link').click(function () {
          $.get('/portfolio', function (data) {
            main_content.html(data);
          });
        })
        $('#resume_link').click( function () {
          $.get('/resume', function (data) {
            main_content.html(data);
          });
        });
      });
    </script>
  </body>
</html>