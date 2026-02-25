// AngularJS controller definíciója
app.controller('PstProcessorController', ['$scope', '$http', function($scope, $http) {

  // Fájl kiválasztása eseménykezelő
  $scope.selectFile = function(file, saveAttachments) {
    var formData = new FormData();
    formData.append('file', file);
    formData.append('saveAttachments', saveAttachments);

    // POST kérés küldése /pst/processFromFile végpontra
    $http.post('/pst/processFromFile', formData, {
      transformRequest: angular.identity,
      headers: {'Content-Type': undefined}
    }).then(function(response) {
      // Sikeres válasz esetén
      alert('Sikeres feldolgozás: ' + response.data);
    }, function(error) {
      // Hibás válasz esetén
      alert('Hiba történt: ' + error.data);
    });
  };

  // TXT fájl feldolgozása eseménykezelő
  $scope.processFromTxt = function(file, saveAttachments) {
    var formData = new FormData();
    formData.append('file', file);
    formData.append('saveAttachments', saveAttachments);

    // POST kérés küldése /pst/processFromTxt végpontra
    $http.post('/pst/processFromTxt', formData, {
      transformRequest: angular.identity,
      headers: {'Content-Type': undefined}
    }).then(function(response) {
      // Sikeres válasz esetén
      alert('Sikeres feldolgozás: ' + response.data);
    }, function(error) {
      // Hibás válasz esetén
      alert('Hiba történt: ' + error.data);
    });
  };

  // Adatbázisból feldolgozás eseménykezelő
  $scope.processFromDb = function(saveAttachments) {
    // POST kérés küldése /pst/processFromDb végpontra
    $http.post('/pst/processFromDb', { saveAttachments: saveAttachments })
      .then(function(response) {
        // Sikeres válasz esetén
        alert('Sikeres feldolgozás: ' + response.data);
      }, function(error) {
        // Hibás válasz esetén
        alert('Hiba történt: ' + error.data);
      });
  };

  // Fájl kiválasztása
  $scope.fileSelected = function(element) {
    $scope.selectedFile = element.files[0];
  };

}]);
