<?php
/**
 * Created by PhpStorm.
 * User: msmith
 * Date: 2/8/14
 * Time: 10:48 PM
 */

  function list_contents($file, $indent) {
    if ($handle = opendir($file)) {
      /* This is the correct way to loop over the directory. */
      while (false !== ($new_file = readdir($handle))) {
        if ($new_file !== '.' && $new_file !== '..') {
          $new_file = $file . DIRECTORY_SEPARATOR . $new_file;
          if (is_dir($new_file)) {
            echo "$indent D: $new_file<br />";
            list_contents($new_file, $indent . '&nbsp;&nbsp;&nbsp;');
          } elseif (is_file($new_file)) {
            echo "$indent F: $new_file<br />";
          } else {
            echo "? $new_file<br />";
          }
        }
      }

      closedir($handle);
    }
  }

  $dir = 'Music';

  list_contents($dir, '');
