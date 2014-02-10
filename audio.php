<?php
/**
 * Created by PhpStorm.
 * User: msmith
 * Date: 2/9/14
 * Time: 7:45 PM
 */

echo '<audio controls="controls">',
  '<source src="',$_GET['file'],'" type="audio/mpeg">',
  'Bad Audio or unsupported format... sorry.</audio>';